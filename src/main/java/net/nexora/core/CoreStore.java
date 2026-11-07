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
                if(version.next() && version.getInt(1)>1){db.close();throw new SQLException("Neuere Datenbankversion wird nicht verändert");}
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
            s.execute("PRAGMA user_version=1");
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
    public synchronized void backup(Path destination) throws SQLException {
        // VACUUM INTO creates a consistent independent backup including committed WAL changes.
        try(PreparedStatement s=db.prepareStatement("VACUUM INTO ?")){s.setString(1,destination.toAbsolutePath().toString());s.execute();}
    }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
