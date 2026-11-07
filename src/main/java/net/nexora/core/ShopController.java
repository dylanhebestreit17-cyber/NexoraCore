package net.nexora.core;

import com.google.gson.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.sql.SQLException;

/** Shop management and delivery. Every Bukkit/inventory operation runs on the server thread. */
final class ShopController implements AutoCloseable {
    private final NexoraCore core;
    private final CoreStore db;
    private ShopSync sync;
    ShopController(NexoraCore core){this.core=core;this.db=core.store;reload();}
    void reload(){if(sync!=null)sync.close();sync=new ShopSync(core,this);}
    void tick(){sync.request();for(Player p:core.getServer().getOnlinePlayers())deliverReady(p);}
    @Override public void close(){if(sync!=null)sync.close();}
    private ShopProduct product(String id) throws SQLException {
        ShopProduct p=db.product(ShopProduct.validId(id));
        if(p==null || p.deleted())throw new IllegalArgumentException("Produkt nicht gefunden");return p;
    }
    private ShopProduct changed(ShopProduct p,String name,long price,String desc,String category,boolean active,String kind,String reward,boolean deleted){
        return new ShopProduct(p.id(),name,price,p.currency(),desc,category,active,kind,reward,p.revision(),deleted);
    }
    private void save(ShopProduct next,ShopProduct old,String actor) throws SQLException {
        if(next.active())validateReward(next.kind(),next.reward());
        db.productChange(next,old==null,old==null?0:old.revision(),actor);
    }
    void manage(CommandSender sender,String[] args) throws Exception {
        core.permission(sender,"nexora.shop.manage");
        String sub=args.length==0?"list":args[0].toLowerCase(Locale.ROOT);
        String actor=core.actor(sender);
        switch(sub){
            case "create" -> {
                if(args.length<5)throw new IllegalArgumentException("nexora shop create <id> <name> <preis> <coins|eur>");
                String id=ShopProduct.validId(args[1]);
                if(core.shop.contains("offers."+id))throw new IllegalArgumentException("ID gehört einem bestehenden Gegenstandsangebot");
                String name=ShopProduct.text(String.join(" ",Arrays.copyOfRange(args,2,args.length-2)),80);
                String currency=ShopProduct.currency(args[args.length-1]);long price=ShopProduct.price(args[args.length-2],currency);
                save(new ShopProduct(id,name,price,currency,"","custom",false,"custom","",1,false),null,actor);
                core.message(sender,"&aProdukt erstellt, zunächst deaktiviert. Belohnung mit nexora shop reward konfigurieren.");
            }
            case "list" -> {
                int page=args.length>1?Integer.parseInt(args[1]):1;if(page<1 || page>100000)throw new IllegalArgumentException("Ungültige Seite");
                List<ShopProduct> products=db.products(Math.multiplyExact(page-1,20),20);
                if(products.isEmpty())core.message(sender,"&eKeine Produkte auf dieser Seite.");
                for(ShopProduct p:products)core.message(sender,"&f"+p.id()+" | "+p.name()+" | "+p.displayPrice()+" | "+p.category()+" | "+(p.deleted()?"gelöscht":p.active()?"aktiv":"deaktiviert")+" | v"+p.revision());
            }
            case "delete","disable","enable" -> {
                if(args.length!=2)throw new IllegalArgumentException("nexora shop "+sub+" <id>");
                ShopProduct p=product(args[1]);save(changed(p,p.name(),p.price(),p.description(),p.category(),sub.equals("enable"),p.kind(),p.reward(),sub.equals("delete")),p,actor);
                core.message(sender,"&aProduktstatus gespeichert; Bestellhistorie bleibt erhalten.");
            }
            case "price" -> {
                if(args.length!=3)throw new IllegalArgumentException("nexora shop price <id> <preis>");
                ShopProduct p=product(args[1]);long price=ShopProduct.price(args[2],p.currency());
                save(changed(p,p.name(),price,p.description(),p.category(),p.active(),p.kind(),p.reward(),false),p,actor);
                core.message(sender,"&aPreis gespeichert: "+product(p.id()).displayPrice());
            }
            case "set" -> {
                if(args.length<4)throw new IllegalArgumentException("nexora shop set <id> <name|description|category> <text>");
                ShopProduct p=product(args[1]);String value=String.join(" ",Arrays.copyOfRange(args,3,args.length));
                ShopProduct next=switch(args[2]){
                    case "name" -> changed(p,ShopProduct.text(value,80),p.price(),p.description(),p.category(),p.active(),p.kind(),p.reward(),false);
                    case "description" -> changed(p,p.name(),p.price(),ShopProduct.text(value,1024),p.category(),p.active(),p.kind(),p.reward(),false);
                    case "category" -> changed(p,p.name(),p.price(),p.description(),ShopProduct.validId(value),p.active(),p.kind(),p.reward(),false);
                    default -> throw new IllegalArgumentException("name, description oder category erwartet");
                };save(next,p,actor);core.message(sender,"&aProdukt bearbeitet.");
            }
            case "reward" -> {
                if(args.length<3)throw new IllegalArgumentException("nexora shop reward <id> <item|custom|kit|rank|coins|cosmetic> [wert]");
                ShopProduct p=product(args[1]);String kind=ShopProduct.kind(args[2]);String reward;
                if(kind.equals("item") || kind.equals("custom")){
                    ItemStack item=core.player(sender).getInventory().getItemInMainHand().clone();
                    if(item.getType().isAir())throw new IllegalArgumentException("Gegenstand in die Haupthand nehmen");reward=ItemPlan.encode(List.of(item));
                }else{if(args.length!=4)throw new IllegalArgumentException("Belohnungswert erforderlich");reward=args[3];}
                validateReward(kind,reward);
                save(changed(p,p.name(),p.price(),p.description(),kind,false,kind,reward,false),p,actor);
                core.message(sender,"&aBelohnung gespeichert. Nach Prüfung mit nexora shop enable "+p.id()+" aktivieren.");
            }
            case "orders" -> {
                int page=args.length>1?Integer.parseInt(args[1]):1;if(page<1 || page>100000)throw new IllegalArgumentException("Ungültige Seite");
                List<CoreStore.ShopOrder> orders=db.orders(Math.multiplyExact(page-1,20),20);
                if(orders.isEmpty())core.message(sender,"&eKeine Bestellungen auf dieser Seite.");
                for(var o:orders)core.message(sender,"&f"+o.id()+" | "+o.product()+" | "+o.buyer()+" | "+o.price()+" "+o.currency()+" (Minor Units) | "+o.status());
            }
            case "review" -> {
                core.permission(sender,"nexora.owner.deliveries");
                if(args.length!=3 || !Set.of("fulfilled","refund").contains(args[2]))throw new IllegalArgumentException("nexora shop review <bestell-id> <fulfilled|refund> – tatsächliche Belohnung zuerst prüfen");
                db.reviewOrder(UUID.fromString(args[1]).toString(),args[2].equals("fulfilled"),actor);core.message(sender,"&aGeprüfte Bestellung abgeschlossen; EUR-Erstattung muss separat beim Zahlungsanbieter erfolgen.");
            }
            case "sync" -> {
                if(!sync.configured())throw new IllegalArgumentException("Shop-Sync nicht konfiguriert. docs/SHOP-BASE44.md beschreibt die erforderliche neue Schnittstelle.");
                sync.request();core.message(sender,"&eSynchronisierung angefragt; Erfolg erst nach passender Bestätigung. "+sync.status());
            }
            default -> throw new IllegalArgumentException("nexora shop create|list|delete|price|set|reward|enable|disable|orders|review|sync");
        }
        if(!Set.of("list","orders","sync").contains(sub))sync.request();
    }
    void listPlayer(Player p) throws SQLException { listPlayer(p,1); }
    void listPlayer(Player p,int page) throws SQLException {
        core.permission(p,"nexora.shop");
        if(page<1 || page>100000)throw new IllegalArgumentException("Ungültige Seite");
        for(ShopProduct product:db.products(Math.multiplyExact(page-1,20),20))if(product.active() && !product.deleted())
            core.message(p,"&f"+product.id()+" | "+product.name()+" | "+product.displayPrice()+" | "+product.category()+" | "+product.description());
    }
    void validateReward(String kind,String reward){
        ShopProduct.kind(kind);if(reward==null || reward.isBlank())throw new IllegalArgumentException("Belohnung fehlt");
        switch(kind){
            case "coins" -> {if(!reward.matches("[1-9][0-9]{0,18}") || Long.parseLong(reward)<=0)throw new IllegalArgumentException("Positiver Coin-Betrag erforderlich");}
            case "kit" -> {ShopProduct.validId(reward);core.catalogItems(core.kits,"kits."+reward+".items");}
            case "rank" -> {
                ShopProduct.validId(reward);
                if(core.ranks==null || !core.ranks.hasGroup(reward) || !core.getConfig().getStringList("shop.allowed-rank-groups").contains(reward))
                    throw new IllegalArgumentException("LuckPerms-Gruppe fehlt oder ist nicht als Shop-Belohnung freigegeben");
            }
            case "cosmetic" -> {
                if(core.ranks==null || !reward.matches("[a-z0-9_.-]{1,120}") || !core.getConfig().getStringList("shop.allowed-cosmetic-permissions").contains(reward))
                    throw new IllegalArgumentException("Kosmetik-Recht nicht freigegeben oder LuckPerms fehlt");
            }
            case "item","custom" -> decode(reward);
            default -> throw new IllegalArgumentException("Unbekannte Belohnung");
        }
    }
    private List<ItemStack> decode(String value){
        if(value.length()>65536)throw new IllegalArgumentException("Itemdaten zu groß");
        try{
            List<ItemStack> items=new ArrayList<>();for(String part:value.split(",")){
                ItemStack item=ItemStack.deserializeBytes(Base64.getDecoder().decode(part));
                if(item.getType().isAir() || item.getAmount()<=0)throw new IllegalArgumentException();items.add(item);
            }
            if(items.isEmpty() || items.size()>36)throw new IllegalArgumentException();return items;
        }catch(RuntimeException e){throw new IllegalArgumentException("Ungültige gespeicherte Itembelohnung");}
    }
    private String snapshot(ShopProduct p){
        validateReward(p.kind(),p.reward());
        return p.kind().equals("kit")?ItemPlan.encode(core.catalogItems(core.kits,"kits."+p.reward()+".items")):p.reward();
    }
    void buy(Player buyer,String id) throws SQLException {
        core.permission(buyer,"nexora.shop");core.local();ShopProduct product=product(id);
        if(!product.currency().equals("coins"))throw new IllegalArgumentException("EUR-Kauf nur über Website mit geprüfter Zahlungsbestätigung möglich");
        String reward=snapshot(product);
        if(Set.of("item","kit","custom").contains(product.kind()))ItemPlan.fit(buyer.getInventory().getStorageContents(),decode(reward),buyer.getInventory().getMaxStackSize());
        CoreStore.ShopOrder order=db.purchase(UUID.randomUUID().toString(),buyer.getUniqueId(),product.id(),product.revision(),product.price(),product.currency(),product.kind(),reward,null);
        deliver(order,buyer);core.queueSnapshot(buyer.getUniqueId());
    }
    void deliverReady(Player p){
        try{
            for(CoreStore.ShopOrder order:db.readyOrders(p.getUniqueId()))deliver(order,p);
        }catch(Exception e){core.getLogger().warning("Shop-Lieferung bleibt zur Prüfung offen ("+e.getClass().getSimpleName()+")");}
    }
    private void deliver(CoreStore.ShopOrder order,Player online) throws SQLException {
        if(!order.status().equals("ready"))return;
        UUID uuid=UUID.fromString(order.buyer());
        switch(order.kind()){
            case "coins" -> {
                // EUR coin bundles require local economy too: never credit a hidden divergent website balance.
                core.local();db.coinOrder(order.id(),Long.parseLong(order.reward()));core.queueSnapshot(uuid);
            }
            case "item","kit","custom" -> {
                if(online==null) return;
                List<ItemStack> items=decode(order.reward());ItemStack[] next=ItemPlan.fit(online.getInventory().getStorageContents(),items,online.getInventory().getMaxStackSize());
                if(!db.claimOrder(order.id(),order.reward()))return;
                try{online.getInventory().setStorageContents(next);online.saveData();db.resolve(order.id(),true,"SHOP");}
                catch(Exception e){throw new IllegalArgumentException("Lieferung unklar; /nexora pending prüfen. Keine automatische Wiederholung.");}
            }
            case "rank","cosmetic" -> {
                validateReward(order.kind(),order.reward());if(!db.claimOrder(order.id(),null))return;
                var future=order.kind().equals("rank")?core.ranks.change(uuid,order.reward(),true):core.ranks.cosmetic(uuid,order.reward());
                future.whenComplete((ignored,error)->core.main(()->{
                    try{
                        if(error==null){db.finishOrder(order.id(),"SHOP");core.queueSnapshot(uuid);}
                        else core.getLogger().warning("Rang-/Kosmetikbestellung bleibt zur Prüfung offen: "+order.id());
                    }catch(SQLException e){core.getLogger().warning("Shop-Abschluss unklar: "+order.id());}
                }));
            }
            default -> throw new IllegalArgumentException("Unbekannte Bestellart");
        }
        if(online!=null)core.message(online,"&aBestellung erfasst: "+order.id()+". Aktueller Status: "+db.order(order.id()).status());
        sync.request();
    }
    /** Explicit new integration contract; called only after authenticated configured shop endpoint responds. */
    String ownerCommand(JsonObject job) throws SQLException {
        String jobId=UUID.fromString(job.get("id").getAsString()).toString();
        String actor=ShopProduct.text(job.get("owner_id").getAsString(),128);
        if(!core.getConfig().getStringList("shop-sync.owner-ids").contains(actor))throw new IllegalArgumentException("Website-Owner nicht freigegeben");
        String cached=db.jobResult(jobId,actor,job.toString());if(cached!=null)return cached;
        String operation=job.get("operation").getAsString();String id=ShopProduct.validId(job.get("product_id").getAsString());
        ShopProduct old=db.product(id);ShopProduct next;boolean create=operation.equals("create");
        if(create){
            if(core.shop.contains("offers."+id))throw new IllegalArgumentException("Bestehendes Legacy-Angebot");
            String currency=ShopProduct.currency(job.get("currency").getAsString());
            next=new ShopProduct(id,ShopProduct.text(job.get("name").getAsString(),80),ShopProduct.price(job.get("price").getAsString(),currency),currency,"","custom",false,"custom","",1,false);
        }else{
            if(old==null || old.deleted())throw new IllegalArgumentException("Produkt existiert nicht");
            next=switch(operation){
                case "price" -> changed(old,old.name(),ShopProduct.price(job.get("price").getAsString(),old.currency()),old.description(),old.category(),old.active(),old.kind(),old.reward(),false);
                case "delete" -> changed(old,old.name(),old.price(),old.description(),old.category(),false,old.kind(),old.reward(),true);
                case "disable","enable" -> changed(old,old.name(),old.price(),old.description(),old.category(),operation.equals("enable"),old.kind(),old.reward(),false);
                case "edit" -> changed(old,ShopProduct.text(job.get("name").getAsString(),80),old.price(),ShopProduct.text(job.get("description").getAsString(),1024),ShopProduct.validId(job.get("category").getAsString()),old.active(),old.kind(),old.reward(),false);
                default -> throw new IllegalArgumentException("Nicht erlaubte Owner-Operation");
            };
        }
        long expected=create?0:Long.parseLong(job.get("expected_revision").getAsString());
        if(next.active())validateReward(next.kind(),next.reward());
        ShopProduct value=next;
        return db.ownerJob(jobId,actor,job.toString(),()->db.productMutation(value,create,expected,"WEB:"+actor));
    }
    String paidOrder(JsonObject request) throws SQLException {
        if(!core.getConfig().getBoolean("shop-sync.accept-paid-orders",false))throw new IllegalArgumentException("Zahlungsannahme ist gesperrt");
        String id=UUID.fromString(request.get("id").getAsString()).toString();
        UUID buyer=UUID.fromString(request.get("buyer_uuid").getAsString());String productId=ShopProduct.validId(request.get("product_id").getAsString());
        long revision=Long.parseLong(request.get("revision").getAsString());
        long price=Long.parseLong(request.get("price_minor").getAsString());
        String reference=ShopProduct.text(request.get("payment_reference").getAsString(),160);
        if(!request.has("payment_status") || !request.get("payment_status").getAsString().equals("paid") || !request.get("currency").getAsString().equals("eur"))throw new IllegalArgumentException("Geprüfte EUR-Zahlung erforderlich");
        CoreStore.ShopOrder existing=db.order(id);
        if(existing!=null){
            if(!existing.buyer().equals(buyer.toString()) || !existing.product().equals(productId) || existing.price()!=price || existing.revision()!=revision
                || !existing.currency().equals("eur") || !Objects.equals(existing.paymentRef(),reference))throw new IllegalArgumentException("Bestell-ID-Konflikt");
            return existing.status();
        }
        ShopProduct product=product(productId);String reward=snapshot(product);
        if(product.kind().equals("coins"))core.local();
        CoreStore.ShopOrder order=db.purchase(id,buyer,productId,revision,price,"eur",product.kind(),reward,reference);
        deliver(order,core.getServer().getPlayer(buyer));return db.order(id).status();
    }
}
