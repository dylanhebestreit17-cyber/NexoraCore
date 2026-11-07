package net.nexora.core;

import com.google.gson.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NexoraCore extends JavaPlugin implements Listener {
    private CoreStore store;
    private RankBridge ranks;
    private volatile NexoraApiClient api;
    private final AtomicBoolean running=new AtomicBoolean();
    private final AtomicBoolean draining=new AtomicBoolean();
    private final Map<UUID,Long> webRequests=new HashMap<>();
    private YamlConfiguration kits,shop;
    private ThreadPoolExecutor http;
    private boolean maintenance;

    @Override public void onEnable(){
        try {
            Files.createDirectories(getDataFolder().toPath());
            saveDefaultConfig();
            if(!Set.of("local","web").contains(mode()))throw new IllegalArgumentException("economy.mode muss local oder web sein");
            // Never rewrite v1 files. Invalid files abort startup instead of resetting player data.
            Path legacy=getDataFolder().toPath().resolve("players.yml");
            if(Files.exists(legacy)){
                YamlConfiguration check=new YamlConfiguration();check.load(legacy.toFile());
                Path backup=getDataFolder().toPath().resolve("players-v1-original.yml.bak");
                if(!Files.exists(backup))Files.copy(legacy,backup);
            }
            store=new CoreStore(getDataFolder().toPath().resolve("nexora.db"));
            store.backup(getDataFolder().toPath().resolve("startup-"+System.currentTimeMillis()+".db.bak"));
            loadCatalogs();
            maintenance=getConfig().getBoolean("maintenance.enabled",false);
            http=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{
                Thread t=new Thread(r,"Nexora-HTTPS");t.setDaemon(true);return t;
            });
            if(getServer().getPluginManager().isPluginEnabled("LuckPerms")){
                var registration=getServer().getServicesManager().getRegistration(net.luckperms.api.LuckPerms.class);
                if(registration!=null)ranks=new RankBridge(registration.getProvider());
            }
            configureApi();running.set(true);
            getServer().getPluginManager().registerEvents(this,this);
            for(Player p:getServer().getOnlinePlayers())store.seen(p.getUniqueId(),p.getName(),System.currentTimeMillis());
            getServer().getScheduler().runTaskTimer(this,()->{
                if(getConfig().getBoolean("website-sync.enabled",false)){
                    try{if(getConfig().getBoolean("website-sync.snapshot-v2",false))for(UUID uuid:store.dirtyPlayers())queueSnapshot(uuid);}
                    catch(SQLException e){getLogger().warning("Sync-Markierungen konnten nicht gelesen werden");}
                    if(getConfig().getBoolean("website-sync.snapshot-v2",false))
                        for(Player p:getServer().getOnlinePlayers())queueSnapshot(p.getUniqueId());
                    drain();
                }
            },100L,1200L);
            getLogger().info("NexoraCore 2.0 gestartet. Coins-Modus: "+mode()+"; API konfiguriert: "+(api!=null));
        }catch(Exception e){
            getLogger().severe("Start abgebrochen; vorhandene Daten bleiben erhalten. Fehlerklasse: "+e.getClass().getSimpleName());
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable(){
        running.set(false);
        if(http!=null)http.shutdownNow();
        if(api!=null)api.close();
        if(store!=null)try{store.close();}catch(SQLException e){getLogger().severe("Datenbank konnte nicht geschlossen werden");}
    }
    private void loadCatalogs() throws IOException,InvalidConfigurationException {
        kits=load("kits.yml");shop=load("shop.yml");
    }
    private YamlConfiguration load(String name) throws IOException,InvalidConfigurationException {
        File file=new File(getDataFolder(),name);
        if(!file.exists())saveResource(name,false);
        YamlConfiguration yaml=new YamlConfiguration();yaml.load(file);return yaml;
    }
    private void saveAtomic(org.bukkit.configuration.file.FileConfiguration yaml,Path target) throws IOException {
        Path temporary=Files.createTempFile(target.getParent(),"nexora-",".tmp");
        try{
            yaml.save(temporary.toFile());
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
    private void configureApi(){
        NexoraApiClient old=api;api=null;if(old!=null)old.close();
        if(!getConfig().getBoolean("website-sync.enabled",false))return;
        try{
            String token=System.getenv("NEXORA_API_TOKEN");
            if(token==null || token.isBlank())token=getConfig().getString("website-sync.token","");
            api=new NexoraApiClient(getConfig().getString("website-sync.endpoint",""),token,
                getConfig().getInt("website-sync.connect-timeout-seconds",5),getConfig().getInt("website-sync.request-timeout-seconds",8));
        }catch(RuntimeException e){getLogger().warning("API bleibt deaktiviert: HTTPS-Endpunkt und echten Token lokal prüfen");}
    }
    private String mode(){return getConfig().getString("economy.mode","web").toLowerCase(Locale.ROOT);}
    private void local(){if(!mode().equals("local"))throw new IllegalArgumentException("Lokale Wirtschaft ist aus; bestehende Website-Coins bleiben getrennt");}
    private void message(CommandSender sender,String text){
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',getConfig().getString("prefix","&6NEXORA &8» ")+text));
    }
    private void permission(CommandSender sender,String node){
        if(!(sender instanceof ConsoleCommandSender) && !sender.hasPermission(node))throw new IllegalArgumentException("Keine Berechtigung: "+node);
    }
    private Player player(CommandSender sender){
        if(!(sender instanceof Player p))throw new IllegalArgumentException("Nur im Spiel verfügbar");return p;
    }
    private String actor(CommandSender sender){return sender instanceof Player p?p.getUniqueId().toString():"CONSOLE";}
    private UUID target(String input) throws SQLException {
        Player p=getServer().getPlayerExact(input);
        UUID uuid=p==null?UUID.fromString(input):p.getUniqueId();
        if(!store.exists(uuid))throw new IllegalArgumentException("Unbekannter Spieler; er muss einmal beigetreten sein");return uuid;
    }
    private String id(String value){
        if(!value.matches("[a-z0-9_-]{1,32}"))throw new IllegalArgumentException("ID: 1–32 Kleinbuchstaben, Ziffern, _ oder -");return value;
    }
    private long amount(String value){
        if(!value.matches("[0-9]{1,19}"))throw new IllegalArgumentException("Nichtnegative ganze Zahl erwartet");
        return Long.parseLong(value);
    }
    private void main(Runnable work){
        if(running.get())try{getServer().getScheduler().runTask(this,()->{if(running.get())work.run();});}
        catch(org.bukkit.plugin.IllegalPluginAccessException ignored){}
    }
    private void submit(Runnable work){
        try{http.execute(work);}catch(RejectedExecutionException e){throw new IllegalArgumentException("Zu viele API-Anfragen, bitte später erneut versuchen");}
    }
    @EventHandler public void onLogin(PlayerLoginEvent event){
        if(maintenance && !event.getPlayer().hasPermission("nexora.maintenance.bypass"))
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,"Nexora Network: Wartungsarbeiten");
    }
    @EventHandler public void onJoin(PlayerJoinEvent event){
        Player p=event.getPlayer();
        try{
            store.seen(p.getUniqueId(),p.getName(),System.currentTimeMillis());
            if(!store.pending(p.getUniqueId()).isEmpty())message(p,"&eEine offene Lieferung benötigt die Prüfung durch einen Owner.");
            if(getConfig().getBoolean("website-sync.sync-on-join",true))queueSnapshot(p.getUniqueId());
        }catch(SQLException e){getLogger().severe("Spielerdaten konnten nicht gespeichert werden");}
    }
    @EventHandler public void onQuit(PlayerQuitEvent event){
        queueSnapshot(event.getPlayer().getUniqueId());webRequests.remove(event.getPlayer().getUniqueId());
    }
    private JsonObject identity(UUID uuid) throws SQLException {
        JsonObject json=new JsonObject();json.addProperty("uuid",uuid.toString());json.addProperty("name",store.name(uuid));
        Player player=getServer().getPlayer(uuid);
        if(player==null){json.addProperty("platform","UNKNOWN");return json;}
        if(getServer().getPluginManager().isPluginEnabled("floodgate")){
            try{
                Class<?> type=Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object fg=type.getMethod("getInstance").invoke(null);
                boolean bedrock=(boolean)type.getMethod("isFloodgatePlayer",UUID.class).invoke(fg,uuid);
                if(bedrock){
                    Object fp=type.getMethod("getPlayer",UUID.class).invoke(fg,uuid);
                    String xuid=String.valueOf(fp.getClass().getMethod("getXuid").invoke(fp));
                    if(!xuid.matches("[0-9]{1,20}"))throw new IllegalArgumentException();
                    json.addProperty("platform","BEDROCK");json.addProperty("xuid",xuid);
                }else json.addProperty("platform","JAVA");
            }catch(ReflectiveOperationException | RuntimeException e){throw new IllegalArgumentException("Floodgate-Identität nicht verifizierbar");}
        }else json.addProperty("platform",getServer().getOnlineMode()?"JAVA":"UNKNOWN");
        return json;
    }
    private void queueSnapshot(UUID uuid){
        if(store==null || !getConfig().getBoolean("website-sync.enabled",false))return;
        try{
            JsonObject payload=identity(uuid);
            boolean v2=getConfig().getBoolean("website-sync.snapshot-v2",false);
            payload.addProperty("action",v2?"player.snapshot":"player.sync");
            String requestId=UUID.randomUUID().toString();
            if(v2){
                String serverId=getConfig().getString("website-sync.server-id","");
                if(!serverId.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("server-id fehlt");
                payload.addProperty("protocol",2);payload.addProperty("server_id",serverId);
                payload.addProperty("request_id",requestId);payload.addProperty("sequence",store.nextSequence());
                if(mode().equals("local"))payload.addProperty("local_coins",store.balance(uuid));
                if(ranks!=null){String rank=ranks.rank(uuid);if(rank!=null)payload.addProperty("luckperms_primary_group",rank);}
            }
            store.enqueue(uuid,requestId,payload.toString(),v2);drain();
        }catch(SQLException | IllegalArgumentException e){getLogger().warning("Sync konnte nicht vorbereitet werden; Konfiguration/Identität prüfen");}
    }
    private void drain(){
        NexoraApiClient client=api;
        if(client==null || !draining.compareAndSet(false,true))return;
        try{submit(()->{
            try{
                for(CoreStore.Pending pending:store.queued()){
                    if(!running.get())break;
                    JsonObject request=JsonParser.parseString(pending.payload()).getAsJsonObject();
                    JsonObject response=client.send(request);
                    if(!NexoraApiClient.ok(response))throw new IOException("API abgelehnt");
                    if(request.has("protocol") && (!response.has("request_id") || !response.get("request_id").isJsonPrimitive()
                        || !response.get("request_id").getAsString().equals(pending.id())))throw new IOException("Falsche Bestätigung");
                    store.acknowledge(pending.id());
                }
            }catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(Exception e){getLogger().warning("API-Sync nicht bestätigt; Warteschlange bleibt für nächsten Versuch erhalten ("+e.getClass().getSimpleName()+")");}
            finally{draining.set(false);}
        });}catch(IllegalArgumentException e){draining.set(false);}
    }
    private void web(Player p,String action){
        NexoraApiClient client=api;if(client==null)throw new IllegalArgumentException("Web-API nicht konfiguriert");
        long now=System.currentTimeMillis();UUID uuid=p.getUniqueId();
        if(now-webRequests.getOrDefault(uuid,0L)<2000)throw new IllegalArgumentException("Bitte zwei Sekunden warten");
        webRequests.put(uuid,now);
        JsonObject payload=new JsonObject();payload.addProperty("action",action);payload.addProperty("uuid",uuid.toString());
        submit(()->{
            String text;
            try{
                JsonObject response=client.send(payload);
                text=action.equals("coins.get")?"&fWebsite-Coins: &e"+NexoraApiClient.coins(response):"&fWebsite-Rang: &e"+NexoraApiClient.rank(response);
            }catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            catch(Exception e){text="&cWebdaten konnten nicht abgerufen werden.";}
            String result=text;main(()->{Player online=getServer().getPlayer(uuid);if(online!=null)message(online,result);});
        });
    }
    private List<ItemStack> catalogItems(YamlConfiguration catalog,String path){
        List<?> list=catalog.getList(path,List.of());List<ItemStack> items=new ArrayList<>();
        for(Object entry:list){
            if(!(entry instanceof ItemStack item) || item.getType().isAir() || item.getAmount()<=0)throw new IllegalArgumentException("Ungültiger Katalogeintrag");
            items.add(item.clone());
        }
        if(items.isEmpty())throw new IllegalArgumentException("Keine Gegenstände konfiguriert");return items;
    }
    private void deliver(Player p,String kind,List<ItemStack> items,long price,long cooldown) throws SQLException {
        ItemStack[] previous=p.getInventory().getStorageContents();
        ItemStack[] next=ItemPlan.fit(previous,items,p.getInventory().getMaxStackSize());
        String delivery=store.reserve(p.getUniqueId(),kind,price,ItemPlan.encode(items),System.currentTimeMillis(),cooldown);
        // Cross-system inventory/DB atomicity is impossible. Keep ambiguous deliveries pending, never auto-replay.
        try{
            p.getInventory().setStorageContents(next);
            p.saveData();
            store.resolve(delivery,true,actor(p));
        }catch(Exception e){
            getLogger().severe("Lieferung "+delivery+" muss manuell geprüft werden; keine automatische Wiederholung/Erstattung");
            throw new IllegalArgumentException("Lieferung unterbrochen. Owner muss ID "+delivery+" prüfen.");
        }
        message(p,"&aGegenstände geliefert. Transaktion: "+delivery);queueSnapshot(p.getUniqueId());
    }
    private void kit(Player p,String[] args) throws SQLException {
        permission(p,"nexora.kit");
        var section=kits.getConfigurationSection("kits");
        if(args.length==0){message(p,"&fKits: "+(section==null?"keine konfiguriert":String.join(", ",section.getKeys(false))));return;}
        String name=id(args[0]);String path="kits."+name;
        if(!kits.isConfigurationSection(path))throw new IllegalArgumentException("Kit existiert nicht");
        permission(p,"nexora.kit."+name);
        long seconds=kits.getLong(path+".cooldown-seconds",0);
        deliver(p,"kit:"+name,catalogItems(kits,path+".items"),0,Math.multiplyExact(seconds,1000));
    }
    private void shop(Player p,String[] args) throws SQLException {
        permission(p,"nexora.shop");local();
        var section=shop.getConfigurationSection("offers");
        if(args.length==0){
            if(section==null || section.getKeys(false).isEmpty())message(p,"&eKeine Shop-Angebote konfiguriert.");
            else for(String key:section.getKeys(false))message(p,"&f"+key+" &e"+shop.getLong("offers."+key+".price")+" Coins &7/shop buy "+key);
            return;
        }
        if(args.length!=2 || !args[0].equalsIgnoreCase("buy"))throw new IllegalArgumentException("/shop buy <id>");
        String name=id(args[1]);String path="offers."+name;
        if(!shop.isConfigurationSection(path))throw new IllegalArgumentException("Angebot existiert nicht");
        String node=shop.getString(path+".permission","");if(!node.isBlank())permission(p,node);
        deliver(p,"shop:"+name,catalogItems(shop,path+".items"),shop.getLong(path+".price",-1),0);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        try{
            switch(command.getName().toLowerCase(Locale.ROOT)){
                case "coins" -> coins(sender,args);
                case "pay" -> {
                    permission(sender,"nexora.pay");local();Player p=player(sender);
                    if(args.length!=2)throw new IllegalArgumentException("/pay <Online-Name|UUID> <Betrag>");
                    UUID to=target(args[0]);store.transfer(p.getUniqueId(),to,amount(args[1]));
                    message(p,"&aCoins übertragen.");queueSnapshot(p.getUniqueId());queueSnapshot(to);
                }
                case "rank" -> {
                    Player p=player(sender);permission(p,"nexora.rank");
                    if(args.length==1 && args[0].equalsIgnoreCase("web")){web(p,"rank.get");break;}
                    String rank=ranks==null?null:ranks.rank(p.getUniqueId());
                    if(rank==null)web(p,"rank.get");else message(p,"&fLuckPerms-Hauptgruppe: &e"+rank);
                }
                case "kit" -> kit(player(sender),args);
                case "shop" -> shop(player(sender),args);
                case "nexora" -> admin(sender,args);
                default -> {return false;}
            }
        }catch(IllegalArgumentException | ArithmeticException e){message(sender,"&c"+(e instanceof NumberFormatException?"Zahl/UUID ungültig":e.getMessage()));}
        catch(Exception e){message(sender,"&cVorgang nicht abgeschlossen; Serverlog prüfen.");getLogger().warning("Vorgang fehlgeschlagen: "+e.getClass().getSimpleName());}
        return true;
    }
    private void coins(CommandSender sender,String[] args) throws SQLException {
        permission(sender,"nexora.coins");
        if(args.length==0 || args.length==1 && Set.of("web","local").contains(args[0].toLowerCase(Locale.ROOT))){
            Player p=player(sender);String selected=args.length==0?mode():args[0].toLowerCase(Locale.ROOT);
            if(selected.equals("web"))web(p,"coins.get");else message(p,"&fLokale Coins: &e"+store.balance(p.getUniqueId()));return;
        }
        permission(sender,"nexora.coins.admin");local();
        if(args.length!=3 || !Set.of("set","add","take").contains(args[0]))throw new IllegalArgumentException("/coins <set|add|take> <Online-Name|UUID> <Betrag>");
        UUID uuid=target(args[1]);store.change(uuid,amount(args[2]),args[0],actor(sender));
        message(sender,"&aNeuer lokaler Kontostand: "+store.balance(uuid));queueSnapshot(uuid);
    }
    private void admin(CommandSender sender,String[] args) throws Exception {
        String sub=args.length==0?"info":args[0].toLowerCase(Locale.ROOT);
        switch(sub){
            case "info" -> message(sender,"&fNexoraCore 2.0 | Coins: "+mode()+" | API konfiguriert: "+(api!=null)+" | LuckPerms: "+(ranks!=null));
            case "reload" -> {
                permission(sender,"nexora.admin");
                YamlConfiguration verified=new YamlConfiguration();verified.load(new File(getDataFolder(),"config.yml"));
                loadCatalogs();reloadConfig();configureApi();maintenance=getConfig().getBoolean("maintenance.enabled",false);
                store.audit(actor(sender),"config.reload","success");message(sender,"&aKonfiguration neu geladen; API-Anmeldung damit noch nicht bestätigt.");
            }
            case "sync" -> {permission(sender,"nexora.admin");Player p=player(sender);
                if(api==null)throw new IllegalArgumentException("API nicht konfiguriert");queueSnapshot(p.getUniqueId());message(sender,"&eSync vorgemerkt; /nexora status zeigt die Warteschlange.");}
            case "status" -> {permission(sender,"nexora.owner.status");message(sender,"&fWartung: "+maintenance+" | Online: "+getServer().getOnlinePlayers().size()+" | offene Lieferungen (max. 50): "+store.pending(null).size()+" | Sync-Einträge (max. 20): "+store.queued().size());}
            case "maintenance" -> {
                permission(sender,"nexora.owner.maintenance");
                if(args.length!=2 || !Set.of("on","off").contains(args[1]))throw new IllegalArgumentException("/nexora maintenance <on|off>");
                boolean value=args[1].equals("on");getConfig().set("maintenance.enabled",value);
                saveAtomic(getConfig(),new File(getDataFolder(),"config.yml").toPath());maintenance=value;
                store.audit(actor(sender),"maintenance",args[1]);message(sender,"&aWartung: "+value+" (gilt für neue Verbindungen)");
            }
            case "backup" -> {
                permission(sender,"nexora.owner.backup");
                store.backup(getDataFolder().toPath().resolve("manual-"+System.currentTimeMillis()+".db.bak"));
                store.audit(actor(sender),"backup","success");message(sender,"&aKonsistentes Datenbank-Backup erstellt. Konfiguration/Kataloge separat sichern.");
            }
            case "audit" -> {permission(sender,"nexora.owner.audit");for(String line:store.recentAudit())message(sender,"&7"+line);}
            case "pending" -> {permission(sender,"nexora.owner.deliveries");for(CoreStore.Delivery d:store.pending(null))message(sender,"&e"+d.id()+" "+d.uuid()+" "+d.kind()+" Preis: "+d.price());}
            case "resolve" -> {
                permission(sender,"nexora.owner.deliveries");
                if(args.length!=3 || !Set.of("delivered","refund").contains(args[2]))throw new IllegalArgumentException("/nexora resolve <ID> <delivered|refund> – erst Spielerinventar prüfen!");
                store.resolve(UUID.fromString(args[1]).toString(),args[2].equals("delivered"),actor(sender));message(sender,"&aLieferung protokolliert abgeschlossen.");
            }
            case "kit-save" -> {
                permission(sender,"nexora.kits.manage");Player p=player(sender);
                if(args.length!=3)throw new IllegalArgumentException("/nexora kit-save <neue-id> <Cooldown-Sekunden>");
                String name=id(args[1]);if(kits.contains("kits."+name))throw new IllegalArgumentException("Kit existiert bereits; Datei bearbeiten");
                List<ItemStack> items=Arrays.stream(p.getInventory().getStorageContents()).filter(i->i!=null && !i.getType().isAir()).map(ItemStack::clone).toList();
                if(items.isEmpty())throw new IllegalArgumentException("Inventar ist leer");
                long seconds=amount(args[2]);Math.multiplyExact(seconds,1000);
                YamlConfiguration next=load("kits.yml");next.set("kits."+name+".items",items);next.set("kits."+name+".cooldown-seconds",seconds);
                saveAtomic(next,new File(getDataFolder(),"kits.yml").toPath());kits=next;
                store.audit(actor(sender),"kit.create",name);message(sender,"&aKit gespeichert; Berechtigung nexora.kit."+name+" separat vergeben.");
            }
            case "shop-add" -> {
                permission(sender,"nexora.shop.manage");Player p=player(sender);
                if(args.length!=3)throw new IllegalArgumentException("/nexora shop-add <neue-id> <Coin-Preis>");
                String name=id(args[1]);if(shop.contains("offers."+name))throw new IllegalArgumentException("Angebot existiert bereits; Datei bearbeiten");
                ItemStack item=p.getInventory().getItemInMainHand().clone();if(item.getType().isAir())throw new IllegalArgumentException("Gegenstand in die Haupthand nehmen");
                long price=amount(args[2]);YamlConfiguration next=load("shop.yml");
                next.set("offers."+name+".items",List.of(item));next.set("offers."+name+".price",price);
                saveAtomic(next,new File(getDataFolder(),"shop.yml").toPath());shop=next;
                store.audit(actor(sender),"shop.create",name+":"+price);message(sender,"&aShop-Angebot gespeichert.");
            }
            case "rank" -> {
                permission(sender,"nexora.ranks.manage");
                if(ranks==null)throw new IllegalArgumentException("LuckPerms ist nicht installiert");
                if(args.length!=4 || !Set.of("add","remove").contains(args[1]))throw new IllegalArgumentException("/nexora rank <add|remove> <Online-Name|UUID> <Gruppe>");
                UUID uuid=target(args[2]);String group=id(args[3]);
                if(!getConfig().getStringList("ranks.manageable-groups").contains(group))throw new IllegalArgumentException("Gruppe nicht in ranks.manageable-groups freigegeben");
                permission(sender,"nexora.ranks.group."+group);
                store.audit(actor(sender),"rank.request",uuid+":"+args[1]+":"+group);
                String who=actor(sender);boolean add=args[1].equals("add");
                ranks.change(uuid,group,add).whenComplete((v,error)->main(()->{
                    try{store.audit(who,error==null?"rank.success":"rank.failed",uuid+":"+group);}catch(SQLException e){getLogger().severe("Rang-Audit fehlgeschlagen");}
                    message(sender,error==null?"&aLuckPerms-Vererbung gespeichert.":"&cLuckPerms-Änderung fehlgeschlagen.");if(error==null)queueSnapshot(uuid);
                }));
            }
            default -> message(sender,"&e/nexora info|reload|sync|status|maintenance|backup|audit|pending|resolve|kit-save|shop-add|rank");
        }
    }
}
