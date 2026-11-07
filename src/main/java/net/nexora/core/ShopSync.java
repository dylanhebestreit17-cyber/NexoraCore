package net.nexora.core;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit opt-in protocol for a new Base44 adapter; never guesses an existing function name. */
final class ShopSync implements AutoCloseable {
    private final NexoraCore core;
    private final ShopController controller;
    private final String serverId;
    private NexoraApiClient client;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final Map<String,JsonObject> acknowledgements=new LinkedHashMap<>();
    private volatile boolean closed;
    private String status="nicht konfiguriert";
    private int productsOffset,ordersOffset;
    ShopSync(NexoraCore core,ShopController controller){
        this.core=core;this.controller=controller;
        serverId=core.getConfig().getString("shop-sync.server-id","");
        if(!core.getConfig().getBoolean("shop-sync.enabled",false))return;
        try{
            if(!serverId.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException();
            String token=System.getenv("NEXORA_SHOP_API_TOKEN");
            if(token==null || token.isBlank())token=core.getConfig().getString("shop-sync.token","");
            client=new NexoraApiClient(core.getConfig().getString("shop-sync.endpoint",""),token,5,15);
            status="konfiguriert, noch nicht bestätigt";
        }catch(RuntimeException e){core.getLogger().warning("Shop-Sync deaktiviert: neuen Endpoint, server-id und lokalen Token prüfen");}
    }
    boolean configured(){return client!=null && !closed;}
    String status(){return status;}
    private JsonObject product(ShopProduct p){
        JsonObject j=new JsonObject();j.addProperty("id",p.id());j.addProperty("name",p.name());
        j.addProperty("price_minor",Long.toString(p.price()));j.addProperty("currency",p.currency());
        j.addProperty("description",p.description());j.addProperty("category",p.category());j.addProperty("type",p.kind());
        j.addProperty("active",p.active());j.addProperty("deleted",p.deleted());j.addProperty("revision",Long.toString(p.revision()));
        // No serialized item payload, credentials, or internal arbitrary permission nodes leave the server.
        String purchaseStatus=p.deleted()?"deleted":!p.active()?"disabled":
            p.currency().equals("eur")&&(!core.getConfig().getBoolean("shop-sync.accept-paid-orders",false)||!configured())?"payment_integration_required":
            p.currency().equals("coins")&&!core.getConfig().getString("economy.mode","web").equalsIgnoreCase("local")?"local_economy_required":"available";
        j.addProperty("purchase_status",purchaseStatus);return j;
    }
    private JsonObject order(CoreStore.ShopOrder o){
        JsonObject j=new JsonObject();j.addProperty("id",o.id());j.addProperty("buyer_uuid",o.buyer());j.addProperty("product_id",o.product());
        j.addProperty("product_revision",Long.toString(o.revision()));j.addProperty("price_minor",Long.toString(o.price()));j.addProperty("currency",o.currency());
        j.addProperty("status",o.status());j.addProperty("created_at",Long.toString(o.created()));return j;
    }
    /** Called on the main thread. SQL reads and JSON snapshot finish before asynchronous HTTPS. */
    void request(){
        if(!configured() || !busy.compareAndSet(false,true))return;
        try{
            String requestId=UUID.randomUUID().toString();JsonObject body=new JsonObject();
            body.addProperty("protocol","nexora.shop/1");body.addProperty("server_id",serverId);body.addProperty("request_id",requestId);
            List<ShopProduct> products=core.store.products(productsOffset,10);List<CoreStore.ShopOrder> orders=core.store.orders(ordersOffset,20);
            JsonArray productArray=new JsonArray();products.forEach(p->productArray.add(product(p)));body.add("products",productArray);
            JsonArray orderArray=new JsonArray();orders.forEach(o->orderArray.add(order(o)));body.add("orders",orderArray);
            body.addProperty("products_offset",productsOffset);body.addProperty("orders_offset",ordersOffset);
            body.addProperty("products_last_page",products.size()<10);body.addProperty("orders_last_page",orders.size()<20);
            List<String> ackIds=acknowledgements.keySet().stream().limit(20).toList();JsonArray acks=new JsonArray();
            ackIds.forEach(id->acks.add(acknowledgements.get(id).deepCopy()));body.add("acknowledgements",acks);
            core.submit(()->{
                try{
                    JsonObject response=client.send(body);
                    if(!NexoraApiClient.ok(response) || !response.has("request_id") || !response.get("request_id").getAsString().equals(requestId))throw new IllegalArgumentException("Falsche Bestätigung");
                    core.main(()->{
                        try{
                            if(closed)return;
                            ackIds.forEach(acknowledgements::remove);
                            productsOffset=products.size()<10?0:productsOffset+10;ordersOffset=orders.size()<20?0:ordersOffset+20;
                            process(response,"owner_commands",false);process(response,"paid_orders",true);
                            status="letzte Seite erfolgreich bestätigt";
                        }catch(RuntimeException e){status="Antwortformat ungültig; nächste Synchronisierung erforderlich";}
                        finally{busy.set(false);}
                    });
                }catch(Exception e){core.main(()->{status="nicht bestätigt; erneuter Versuch im Minutentakt";busy.set(false);});}
            });
        }catch(Exception e){status="Synchronisierung konnte nicht vorbereitet werden";busy.set(false);}
    }
    private void process(JsonObject response,String field,boolean paid){
        if(!response.has(field))return;
        JsonArray jobs=response.getAsJsonArray(field);if(jobs.size()>20)throw new IllegalArgumentException("Batch zu groß");
        for(JsonElement e:jobs){
            JsonObject job=e.getAsJsonObject();String id;
            try{id=UUID.fromString(job.get("id").getAsString()).toString();}catch(RuntimeException invalid){continue;}
            JsonObject ack=new JsonObject();ack.addProperty("id",id);ack.addProperty("kind",paid?"order":"owner_command");
            try{
                String result=paid?controller.paidOrder(job):controller.ownerCommand(job);
                ack.addProperty("result",result);
            }catch(Exception failure){
                ack.addProperty("result","rejected");ack.addProperty("reason","server_validation_failed");
                core.getLogger().warning("Shop-Adapter-Eintrag abgewiesen: "+id+" ("+failure.getClass().getSimpleName()+")");
            }
            acknowledgements.put((paid?"order:":"job:")+id,ack);
        }
    }
    @Override public void close(){closed=true;if(client!=null)client.close();}
}
