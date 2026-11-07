package net.nexora.core;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Single-server durable ledger. Every mutation and its audit record commit together. */
public final class CoreStore implements AutoCloseable {
    private final Connection db;
    public record Delivery(String id, String uuid, String kind, long price, String items, long previousReady) {}
    public record Pending(String id, String payload) {}
    public CoreStore(Path file) throws SQLException {
        db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            try(ResultSet version=s.executeQuery("PRAGMA user_version")){
                if(version.next() && version.getInt(1)>2){db.close();throw new SQLException("Neuere Datenbankversion wird nicht verändert");}
            }
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("CREATE TABLE IF NOT EXISTS accounts(uuid TEXT PRIMARY KEY, name TEXT NOT NULL, coins INTEGER NOT NULL DEFAULT 0 CHECK(coins>=0), last_seen INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS cooldowns(uuid TEXT NOT NULL, kit TEXT NOT NULL, ready INTEGER NOT NULL, PRIMARY KEY(uuid,kit))");
            s.execute("CREATE TABLE IF NOT EXISTS deliveries(id TEXT PRIMARY KEY, uuid TEXT NOT NULL REFERENCES accounts(uuid), kind TEXT NOT NULL, price INTEGER NOT NULL, items TEXT NOT NULL, previous_ready INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending')");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_delivery ON deliveries(uuid) WHERE state='pending'");
            s.execute("CREATE TABLE IF NOT EXISTS outbox(id TEXT PRIMARY KEY, uuid TEXT NOT NULL UNIQUE, payload TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS sync_meta(id INTEGER PRIMARY KEY CHECK(id=1), sequence INTEGER NOT NULL)");
            s.execute("INSERT OR IGNORE INTO sync_meta(id,sequence) VALUES(1,0)");
            s.execute("CREATE TABLE IF NOT EXISTS dirty(uuid TEXT PRIMARY KEY)");
            // Backup before adding shop schema or updating schema version.
            int schema;
            try(ResultSet v=s.executeQuery("PRAGMA user_version")){v.next();schema=v.getInt(1);}
            if(schema<2){
                try(PreparedStatement backup=db.prepareStatement("VACUUM INTO ?")){
                    backup.setString(1,file.toAbsolutePath()+".pre-shop-"+UUID.randomUUID()+".bak");backup.execute();
                }
            }
            s.execute("CREATE TABLE IF NOT EXISTS shop_products(id TEXT PRIMARY KEY,name TEXT NOT NULL,price INTEGER NOT NULL CHECK(price>=0),currency TEXT NOT NULL CHECK(currency IN ('coins','eur')),description TEXT NOT NULL DEFAULT '',category TEXT NOT NULL DEFAULT 'custom',active INTEGER NOT NULL DEFAULT 0,kind TEXT NOT NULL DEFAULT 'custom',reward TEXT NOT NULL DEFAULT '',revision INTEGER NOT NULL DEFAULT 1,deleted INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS shop_orders(id TEXT PRIMARY KEY,buyer TEXT NOT NULL REFERENCES accounts(uuid),product TEXT NOT NULL,revision INTEGER NOT NULL,price INTEGER NOT NULL,currency TEXT NOT NULL,status TEXT NOT NULL,kind TEXT NOT NULL,reward TEXT NOT NULL,delivery TEXT,payment_ref TEXT UNIQUE,created INTEGER NOT NULL,detail TEXT NOT NULL DEFAULT '')");
            s.execute("CREATE TABLE IF NOT EXISTS shop_jobs(id TEXT PRIMARY KEY,actor TEXT NOT NULL,request TEXT NOT NULL,result TEXT NOT NULL)");
            s.execute("PRAGMA user_version=2");
        }
    }
    @FunctionalInterface private interface Work<T> { T run() throws SQLException; }
    private synchronized <T> T transaction(Work<T> work) throws SQLException {
        db.setAutoCommit(false);
        try { T value=work.run(); db.commit(); return value; }
        catch (SQLException | RuntimeException e) { db.rollback(); throw e; }
        finally { db.setAutoCommit(true); }
    }
    private void update(String sql, Object... values) throws SQLException {
        try (PreparedStatement s=db.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++) s.setObject(i+1,values[i]);
            s.executeUpdate();
        }
    }
    public synchronized void seen(UUID uuid, String name, long at) throws SQLException {
        update("INSERT INTO accounts(uuid,name,last_seen) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen",uuid.toString(),name,at);
    }
    public synchronized boolean exists(UUID uuid) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("SELECT 1 FROM accounts WHERE uuid=?")) {
            s.setString(1,uuid.toString()); try(ResultSet r=s.executeQuery()){return r.next();}
        }
    }
    public synchronized long balance(UUID uuid) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("SELECT coins FROM accounts WHERE uuid=?")) {
            s.setString(1,uuid.toString()); try(ResultSet r=s.executeQuery()){
                if(!r.next()) throw new IllegalArgumentException("Spieler ist lokal nicht registriert (UUID verwenden)");
                return r.getLong(1);
            }
        }
    }
    public synchronized void audit(String actor,String action,String detail) throws SQLException {
        update("INSERT INTO audit(at,actor,action,detail) VALUES(?,?,?,?)",System.currentTimeMillis(),actor,action,detail);
    }
    public void change(UUID uuid,long amount,String operation,String actor) throws SQLException {
        transaction(()->{
            long old=balance(uuid);
            long value=switch(operation){
                case "set" -> amount;
                case "add" -> Math.addExact(old,amount);
                case "take" -> Math.subtractExact(old,amount);
                default -> throw new IllegalArgumentException("set/add/take erwartet");
            };
            if(amount<0 || value<0) throw new IllegalArgumentException("Ungültiger Betrag oder zu wenig Coins");
            update("UPDATE accounts SET coins=? WHERE uuid=?",value,uuid.toString());
            update("INSERT OR IGNORE INTO dirty(uuid) VALUES(?)",uuid.toString());
            audit(actor,"coins."+operation,uuid+":"+old+"->"+value); return null;
        });
    }
    public void transfer(UUID from,UUID to,long amount) throws SQLException {
        if(from.equals(to) || amount<=0) throw new IllegalArgumentException("Anderer Spieler und positiver Betrag erforderlich");
        transaction(()->{
            long debit=Math.subtractExact(balance(from),amount);
            long credit=Math.addExact(balance(to),amount);
            if(debit<0) throw new IllegalArgumentException("Zu wenig Coins");
            update("UPDATE accounts SET coins=? WHERE uuid=?",debit,from.toString());
            update("UPDATE accounts SET coins=? WHERE uuid=?",credit,to.toString());
            update("INSERT OR IGNORE INTO dirty(uuid) VALUES(?)",from.toString());
            update("INSERT OR IGNORE INTO dirty(uuid) VALUES(?)",to.toString());
            audit(from.toString(),"coins.pay",to+":"+amount);return null;
        });
    }
    public synchronized long ready(UUID uuid,String kit) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("SELECT ready FROM cooldowns WHERE uuid=? AND kit=?")) {
            s.setString(1,uuid.toString());s.setString(2,kit);
            try(ResultSet r=s.executeQuery()){return r.next()?r.getLong(1):0;}
        }
    }
    public String reserve(UUID uuid,String kind,long price,String items,long now,long cooldownMs) throws SQLException {
        if(price<0 || cooldownMs<0)throw new IllegalArgumentException("Negative Werte sind unzulässig");
        return transaction(()->{
            if(!pending(uuid).isEmpty()) throw new IllegalArgumentException("Offene Lieferung: Owner muss sie zuerst prüfen");
            long oldReady=ready(uuid,kind);
            if(kind.startsWith("kit:") && oldReady>now) throw new IllegalArgumentException("Kit noch "+((oldReady-now+999)/1000)+" Sekunden gesperrt");
            long value=Math.subtractExact(balance(uuid),price);
            if(value<0)throw new IllegalArgumentException("Zu wenig Coins");
            String id=UUID.randomUUID().toString();
            update("UPDATE accounts SET coins=? WHERE uuid=?",value,uuid.toString());
            update("INSERT INTO deliveries(id,uuid,kind,price,items,previous_ready) VALUES(?,?,?,?,?,?)",id,uuid.toString(),kind,price,items,oldReady);
            if(kind.startsWith("kit:"))update("INSERT INTO cooldowns(uuid,kit,ready) VALUES(?,?,?) ON CONFLICT(uuid,kit) DO UPDATE SET ready=excluded.ready",uuid.toString(),kind,Math.addExact(now,cooldownMs));
            update("INSERT OR IGNORE INTO dirty(uuid) VALUES(?)",uuid.toString());
            audit(uuid.toString(),"delivery.reserve",id+":"+kind+":"+price);return id;
        });
    }
    public void resolve(String id,boolean delivered,String actor) throws SQLException {
        transaction(()->{
            Delivery d;
            try(PreparedStatement s=db.prepareStatement("SELECT id,uuid,kind,price,items,previous_ready FROM deliveries WHERE id=? AND state='pending'")) {
                s.setString(1,id); try(ResultSet r=s.executeQuery()){
                    if(!r.next())throw new IllegalArgumentException("Keine offene Lieferung mit dieser ID");
                    d=new Delivery(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getLong(6));
                }
            }
            if(!delivered){
                long value=Math.addExact(balance(UUID.fromString(d.uuid())),d.price());
                update("UPDATE accounts SET coins=? WHERE uuid=?",value,d.uuid());
                if(d.kind().startsWith("kit:"))update("UPDATE cooldowns SET ready=? WHERE uuid=? AND kit=?",d.previousReady(),d.uuid(),d.kind());
            }
            update("UPDATE deliveries SET state=? WHERE id=?",delivered?"delivered":"refunded",id);
            update("INSERT OR IGNORE INTO dirty(uuid) VALUES(?)",d.uuid());
            update("UPDATE shop_orders SET status=CASE WHEN ? THEN 'fulfilled' WHEN currency='eur' THEN 'refund_required' ELSE 'refunded' END WHERE delivery=? AND status='delivering'",delivered,id);
            audit(actor,delivered?"delivery.complete":"delivery.refund",id);return null;
        });
    }
    public synchronized List<Delivery> pending(UUID uuid) throws SQLException {
        List<Delivery> result=new ArrayList<>();
        try(PreparedStatement s=db.prepareStatement("SELECT id,uuid,kind,price,items,previous_ready FROM deliveries WHERE state='pending' AND (? IS NULL OR uuid=?) ORDER BY rowid LIMIT 50")) {
            s.setString(1,uuid==null?null:uuid.toString());s.setString(2,uuid==null?null:uuid.toString());
            try(ResultSet r=s.executeQuery()){while(r.next())result.add(new Delivery(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getLong(6)));}
        }return result;
    }
    public synchronized List<String> recentAudit() throws SQLException {
        List<String> lines=new ArrayList<>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT at,actor,action,detail FROM audit ORDER BY id DESC LIMIT 10")){
            while(r.next())lines.add(r.getLong(1)+" | "+r.getString(2)+" | "+r.getString(3)+" | "+r.getString(4));
        }return lines;
    }
    public synchronized String name(UUID uuid) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("SELECT name FROM accounts WHERE uuid=?")){
            s.setString(1,uuid.toString());try(ResultSet r=s.executeQuery()){
                if(!r.next())throw new IllegalArgumentException("Unbekannte UUID");return r.getString(1);
            }
        }
    }
    public long nextSequence() throws SQLException {
        return transaction(()->{
            update("UPDATE sync_meta SET sequence=sequence+1 WHERE id=1");
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT sequence FROM sync_meta WHERE id=1")){r.next();return r.getLong(1);}
        });
    }
    public synchronized void enqueue(UUID uuid,String id,String payload) throws SQLException { enqueue(uuid,id,payload,false); }
    public synchronized void enqueue(UUID uuid,String id,String payload,boolean snapshot) throws SQLException {
        transaction(()->{update("INSERT INTO outbox(id,uuid,payload) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET id=excluded.id,payload=excluded.payload",id,uuid.toString(),payload);
        if(snapshot)update("DELETE FROM dirty WHERE uuid=?",uuid.toString());return null;});
    }
    public synchronized List<UUID> dirtyPlayers() throws SQLException {
        List<UUID> result=new ArrayList<>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT uuid FROM dirty LIMIT 100")){
            while(r.next())result.add(UUID.fromString(r.getString(1)));
        }return result;
    }
    public synchronized List<Pending> queued() throws SQLException {
        List<Pending> result=new ArrayList<>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,payload FROM outbox ORDER BY rowid LIMIT 20")) {
            while(r.next())result.add(new Pending(r.getString(1),r.getString(2)));
        }return result;
    }
    public synchronized void acknowledge(String id) throws SQLException {update("DELETE FROM outbox WHERE id=?",id);}
    public record ShopOrder(String id,String buyer,String product,long revision,long price,String currency,String status,String kind,String reward,String delivery,String paymentRef,long created,String detail) {}
    @FunctionalInterface public interface ShopWork { void run() throws SQLException; }
    public synchronized String ownerJob(String id,String actor,String request,ShopWork work) throws SQLException {
        return transaction(()->{
            try(PreparedStatement s=db.prepareStatement("SELECT actor,request,result FROM shop_jobs WHERE id=?")){
                s.setString(1,id);try(ResultSet r=s.executeQuery()){
                    if(r.next()){
                        if(!r.getString(1).equals(actor) || !r.getString(2).equals(request))throw new IllegalArgumentException("Job-ID mit anderem Inhalt wiederverwendet");
                        return r.getString(3);
                    }
                }
            }
            // work is productMutation(), which runs inside this transaction (no nested transaction).
            work.run();update("INSERT INTO shop_jobs(id,actor,request,result) VALUES(?,?,?,?)",id,actor,request,"applied");
            audit(actor,"shop.owner-job",id);return "applied";
        });
    }
    public synchronized String jobResult(String id,String actor,String request) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("SELECT actor,request,result FROM shop_jobs WHERE id=?")){
            s.setString(1,id);try(ResultSet r=s.executeQuery()){
                if(!r.next())return null;
                if(!r.getString(1).equals(actor) || !r.getString(2).equals(request))throw new IllegalArgumentException("Job-ID-Konflikt");return r.getString(3);
            }
        }
    }
    public synchronized List<ShopOrder> readyOrders(UUID buyer) throws SQLException {
        List<ShopOrder> result=new ArrayList<>();
        try(PreparedStatement s=db.prepareStatement("SELECT * FROM shop_orders WHERE buyer=? AND status='ready' ORDER BY rowid LIMIT 20")){
            s.setString(1,buyer.toString());try(ResultSet r=s.executeQuery()){while(r.next())result.add(orderRow(r));}
        }return result;
    }
    private ShopProduct productRow(ResultSet r) throws SQLException {
        return new ShopProduct(r.getString("id"),r.getString("name"),r.getLong("price"),r.getString("currency"),r.getString("description"),r.getString("category"),r.getBoolean("active"),r.getString("kind"),r.getString("reward"),r.getLong("revision"),r.getBoolean("deleted"));
    }
    public synchronized ShopProduct product(String id) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("SELECT * FROM shop_products WHERE id=?")){
            s.setString(1,id);try(ResultSet r=s.executeQuery()){return r.next()?productRow(r):null;}
        }
    }
    public synchronized List<ShopProduct> products(int offset,int limit) throws SQLException {
        if(offset<0 || limit<1 || limit>100)throw new IllegalArgumentException("Ungültige Seitengröße");
        List<ShopProduct> result=new ArrayList<>();
        try(PreparedStatement s=db.prepareStatement("SELECT * FROM shop_products ORDER BY rowid LIMIT ? OFFSET ?")){
            s.setInt(1,limit);s.setInt(2,offset);try(ResultSet r=s.executeQuery()){while(r.next())result.add(productRow(r));}
        }return result;
    }
    public void productChange(ShopProduct next,boolean create,long expected,String actor) throws SQLException {
        transaction(()->{productMutation(next,create,expected,actor);return null;});
    }
    /** Called by normal productChange or ownerJob, always inside one transaction. */
    public synchronized void productMutation(ShopProduct p,boolean create,long expected,String actor) throws SQLException {
        ShopProduct.validId(p.id());ShopProduct.text(p.name(),80);ShopProduct.currency(p.currency());ShopProduct.kind(p.kind());
        if(p.price()<0 || p.description()==null || p.description().length()>1024 || p.description().chars().anyMatch(Character::isISOControl)
            || p.category()==null || !p.category().matches("[a-z0-9_-]{1,32}") || p.reward()==null || p.reward().length()>65536)
            throw new IllegalArgumentException("Ungültige Produktdaten");
        if(p.active() && (p.deleted() || p.reward().isBlank()))throw new IllegalArgumentException("Aktives Produkt benötigt eine konfigurierte Belohnung");
        ShopProduct old=product(p.id());
        if(create){
            if(old!=null)throw new IllegalArgumentException("Produkt-ID existiert bereits (auch gelöschte IDs bleiben reserviert)");
            update("INSERT INTO shop_products(id,name,price,currency,description,category,active,kind,reward) VALUES(?,?,?,?,?,?,?,?,?)",p.id(),p.name(),p.price(),p.currency(),p.description(),p.category(),p.active(),p.kind(),p.reward());
        }else{
            if(old==null || old.deleted())throw new IllegalArgumentException("Produkt existiert nicht");
            if(old.revision()!=expected)throw new IllegalArgumentException("Produkt wurde inzwischen geändert; neu laden");
            update("UPDATE shop_products SET name=?,price=?,currency=?,description=?,category=?,active=?,kind=?,reward=?,revision=revision+1,deleted=? WHERE id=?",p.name(),p.price(),p.currency(),p.description(),p.category(),p.active(),p.kind(),p.reward(),p.deleted(),p.id());
        }
        audit(actor,create?"shop.create":"shop.edit",p.id()+":"+p.price()+":"+p.currency());
    }
    private ShopOrder orderRow(ResultSet r) throws SQLException {
        return new ShopOrder(r.getString("id"),r.getString("buyer"),r.getString("product"),r.getLong("revision"),r.getLong("price"),r.getString("currency"),r.getString("status"),r.getString("kind"),r.getString("reward"),r.getString("delivery"),r.getString("payment_ref"),r.getLong("created"),r.getString("detail"));
    }
    public synchronized ShopOrder order(String id) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("SELECT * FROM shop_orders WHERE id=?")){
            s.setString(1,id);try(ResultSet r=s.executeQuery()){return r.next()?orderRow(r):null;}
        }
    }
    public synchronized List<ShopOrder> orders(int offset,int limit) throws SQLException {
        if(offset<0 || limit<1 || limit>100)throw new IllegalArgumentException("Ungültige Seitengröße");
        List<ShopOrder> result=new ArrayList<>();
        try(PreparedStatement s=db.prepareStatement("SELECT * FROM shop_orders ORDER BY rowid LIMIT ? OFFSET ?")){
            s.setInt(1,limit);s.setInt(2,offset);try(ResultSet r=s.executeQuery()){while(r.next())result.add(orderRow(r));}
        }return result;
    }
    public ShopOrder purchase(String orderId,UUID buyer,String productId,long revision,long price,String currency,String kind,String reward,String paymentRef) throws SQLException {
        UUID.fromString(orderId);
        return transaction(()->{
            ShopOrder existing=order(orderId);
            if(existing!=null){
                if(!existing.buyer().equals(buyer.toString()) || !existing.product().equals(productId) || existing.revision()!=revision
                    || existing.price()!=price || !existing.currency().equals(currency) || !Objects.equals(existing.paymentRef(),paymentRef))
                    throw new IllegalArgumentException("Bestell-ID mit anderen Daten wiederverwendet");
                return existing;
            }
            ShopProduct product=product(productId);
            if(product==null || !product.active() || product.deleted())throw new IllegalArgumentException("Produkt ist nicht kaufbar");
            if(product.revision()!=revision || product.price()!=price || !product.currency().equals(currency) || !product.kind().equals(kind))
                throw new IllegalArgumentException("Preis oder Produktversion geändert; neue Bestellung erforderlich");
            if(reward==null || reward.isBlank() || (!kind.equals("kit") && !product.reward().equals(reward)))throw new IllegalArgumentException("Belohnung stimmt nicht mit Produkt überein");
            long before=balance(buyer);
            if(currency.equals("eur")){
                if(paymentRef==null || !paymentRef.matches("[a-zA-Z0-9_:.\\-]{1,160}"))throw new IllegalArgumentException("Geprüfte eindeutige Zahlungsreferenz erforderlich");
            }else{
                if(paymentRef!=null)throw new IllegalArgumentException("Coin-Bestellung darf keine Zahlungsreferenz enthalten");
                if(before<price)throw new IllegalArgumentException("Zu wenig Coins");
                update("UPDATE accounts SET coins=? WHERE uuid=?",before-price,buyer.toString());
            }
            update("INSERT INTO shop_orders(id,buyer,product,revision,price,currency,status,kind,reward,payment_ref,created) VALUES(?,?,?,?,?,?,'ready',?,?,?,?)",orderId,buyer.toString(),productId,revision,price,currency,kind,reward,paymentRef,System.currentTimeMillis());
            update("INSERT OR IGNORE INTO dirty(uuid) VALUES(?)",buyer.toString());
            audit(buyer.toString(),"shop.purchase",orderId+":"+productId+":"+price+":"+currency);
            return order(orderId);
        });
    }
    /** Claim external inventory/LP effect once. A crash leaves delivering for manual review. */
    public boolean claimOrder(String id,String deliveryItems) throws SQLException {
        return transaction(()->{
            ShopOrder o=order(id);if(o==null || !o.status().equals("ready"))return false;
            UUID buyer=UUID.fromString(o.buyer());
            if(deliveryItems!=null){
                if(!pending(buyer).isEmpty())throw new IllegalArgumentException("Offene Lieferung zuerst prüfen");
                update("INSERT INTO deliveries(id,uuid,kind,price,items,previous_ready) VALUES(?,?,?,?,?,0)",id,o.buyer(),"shoporder:"+id,o.currency().equals("coins")?o.price():0,deliveryItems);
            }
            update("UPDATE shop_orders SET status='delivering',delivery=? WHERE id=?",deliveryItems==null?null:id,id);
            audit("SYSTEM","shop.claim",id);return true;
        });
    }
    public void finishOrder(String id,String actor) throws SQLException {
        transaction(()->{
            ShopOrder o=order(id);if(o==null || !o.status().equals("delivering"))throw new IllegalArgumentException("Keine laufende Bestellung");
            if(o.delivery()!=null)throw new IllegalArgumentException("Inventarlieferung über Lieferungsjournal abschließen");
            update("UPDATE shop_orders SET status='fulfilled' WHERE id=?",id);audit(actor,"shop.fulfilled",id);return null;
        });
    }
    public void coinOrder(String id,long coins) throws SQLException {
        if(coins<=0)throw new IllegalArgumentException("Positives Coin-Paket erforderlich");
        transaction(()->{
            ShopOrder o=order(id);if(o==null || !o.status().equals("ready"))return null;
            if(!o.kind().equals("coins") || !o.reward().equals(Long.toString(coins)))throw new IllegalArgumentException("Falsche Belohnung");
            long value=Math.addExact(balance(UUID.fromString(o.buyer())),coins);
            update("UPDATE accounts SET coins=? WHERE uuid=?",value,o.buyer());
            update("UPDATE shop_orders SET status='fulfilled' WHERE id=?",id);
            update("INSERT OR IGNORE INTO dirty(uuid) VALUES(?)",o.buyer());audit("SYSTEM","shop.coins-delivered",id);return null;
        });
    }
    public void reviewOrder(String id,boolean fulfilled,String actor) throws SQLException {
        transaction(()->{
            ShopOrder o=order(id);
            if(o==null || !Set.of("ready","delivering").contains(o.status()) || o.delivery()!=null)throw new IllegalArgumentException("Keine manuell prüfbare Bestellung; Inventarlieferung über resolve prüfen");
            if(!fulfilled){
                if(o.currency().equals("coins")){
                    long value=Math.addExact(balance(UUID.fromString(o.buyer())),o.price());
                    update("UPDATE accounts SET coins=? WHERE uuid=?",value,o.buyer());
                    update("INSERT OR IGNORE INTO dirty(uuid) VALUES(?)",o.buyer());
                }
            }
            String state=fulfilled?"fulfilled":o.currency().equals("eur")?"refund_required":"refunded";
            update("UPDATE shop_orders SET status=?,detail='owner-reviewed' WHERE id=?",state,id);audit(actor,"shop.review",id+":"+state);return null;
        });
    }
    public synchronized void backup(Path destination) throws SQLException {
        // VACUUM INTO creates a consistent independent backup including committed WAL changes.
        try(PreparedStatement s=db.prepareStatement("VACUUM INTO ?")){s.setString(1,destination.toAbsolutePath().toString());s.execute();}
    }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
