package net.nexora.core;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

public final class NexoraCore extends JavaPlugin implements Listener {
    private NexoraApiClient api;
    private File playerFile;
    private YamlConfiguration players;

    @Override public void onEnable() {
        saveDefaultConfig();
        playerFile = new File(getDataFolder(), "players.yml");
        players = YamlConfiguration.loadConfiguration(playerFile);
        configureApi();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("NexoraCore 1.2 gestartet; Web-Sync " + (api == null ? "deaktiviert" : "konfiguriert"));
    }

    private void configureApi() {
        api = null;
        if (!getConfig().getBoolean("website-sync.enabled")) return;
        try {
            api = new NexoraApiClient(
                    getConfig().getString("website-sync.endpoint", ""),
                    getConfig().getString("website-sync.token", ""),
                    getConfig().getInt("website-sync.connect-timeout-seconds", 5),
                    getConfig().getInt("website-sync.request-timeout-seconds", 8));
        } catch (RuntimeException ex) {
            getLogger().warning("Web-Sync bleibt aus: " + ex.getMessage());
        }
    }

    private String format(String message) {
        return ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("prefix", "&6NEXORA » ") + message);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String path = "players." + player.getUniqueId();
        players.set(path + ".name", player.getName());
        players.set(path + ".last_seen", System.currentTimeMillis());
        savePlayers();
        if (getConfig().getBoolean("website-sync.sync-on-join", true)) sync(player);
    }

    private void savePlayers() {
        try { players.save(playerFile); }
        catch (IOException ex) { getLogger().warning("Spielerdaten konnten nicht gespeichert werden"); }
    }

    private void sync(Player player) {
        NexoraApiClient client = api;
        if (client == null) return;
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        // Floodgate-UUIDs werden NICHT anhand eines geratenen Präfixes identifiziert.
        // Ohne verifizierte Floodgate-API-Anbindung bleibt die Edition unbekannt.
        JsonObject request = new JsonObject();
        request.addProperty("action", "player.sync");
        request.addProperty("uuid", uuid.toString());
        request.addProperty("name", name);
        // Detect Floodgate Bedrock players only through its actual API, never via name/UUID guesses.
        try {
            Class<?> floodgate = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = floodgate.getMethod("getInstance").invoke(null);
            boolean bedrock = (boolean) floodgate.getMethod("isFloodgatePlayer", UUID.class).invoke(instance, uuid);
            if (bedrock) {
                Object fgPlayer = floodgate.getMethod("getPlayer", UUID.class).invoke(instance, uuid);
                String xuid = String.valueOf(fgPlayer.getClass().getMethod("getXuid").invoke(fgPlayer));
                if (!xuid.matches("[0-9]{1,20}")) {
                    getLogger().warning("Ungültige Floodgate-XUID, Sync übersprungen für " + uuid);
                    return;
                }
                request.addProperty("platform", "BEDROCK");
                request.addProperty("xuid", xuid);
            } else request.addProperty("platform", "JAVA");
        } catch (ClassNotFoundException ex) {
            if (getServer().getPluginManager().isPluginEnabled("floodgate")) {
                getLogger().warning("Floodgate-API nicht gefunden; Sync zur Sicherheit übersprungen");
                return;
            }
            request.addProperty("platform", "JAVA");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            getLogger().warning("Floodgate-Spieler konnte nicht sicher identifiziert werden; Sync übersprungen");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                JsonObject response = client.send(request);
                getLogger().info("Sync-Antwort für " + uuid + " empfangen (Inhalt nicht protokolliert)");
                // Kein Überschreiben von Coins/Rängen bei Sync.
                if (!isOk(response)) {
                    getLogger().warning("Sync wurde von der API abgelehnt für " + uuid);
                }
            } catch (Exception ex) {
                getLogger().warning("Sync fehlgeschlagen für " + uuid + ": " + ex.getClass().getSimpleName());
            }
        });
    }

    private boolean isOk(JsonObject response) {
        return response.has("ok") && response.get("ok").isJsonPrimitive()
                && response.get("ok").getAsBoolean();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "coins", "rank" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(format("&cNur im Spiel verfügbar")); return true;
                }
                NexoraApiClient client = api;
                if (client == null) {
                    sender.sendMessage(format("&cWeb-API nicht konfiguriert")); return true;
                }
                String action = command.getName().equalsIgnoreCase("coins") ? "coins.get" : "rank.get";
                UUID playerId = player.getUniqueId();
                JsonObject payload = new JsonObject();
                payload.addProperty("action", action);
                payload.addProperty("uuid", playerId.toString());
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    String message;
                    try {
                        JsonObject response = client.send(payload);
                        if (!isOk(response) || !response.has("data") || !response.get("data").isJsonObject())
                            throw new IOException("API hat keine gültigen Daten geliefert");
                        JsonObject data = response.getAsJsonObject("data");
                        if (action.equals("coins.get")) {
                            if (!data.has("coins") || !data.get("coins").isJsonPrimitive()) throw new IOException("coins fehlt");
                            long coins = data.get("coins").getAsLong();
                            if (coins < 0) throw new IOException("Ungültiger Kontostand");
                            message = "&fDeine Coins: &e" + coins;
                        } else {
                            if (!data.has("rank") || !data.get("rank").isJsonPrimitive()) throw new IOException("rank fehlt");
                            String rank = data.get("rank").getAsString();
                            if (!rank.matches("[a-zA-Z0-9_-]{1,32}")) throw new IOException("Ungültiger Rang");
                            message = "&fDein Rang: &e" + rank;
                        }
                    } catch (Exception ex) {
                        message = "&cWebdaten konnten nicht abgerufen werden.";
                        getLogger().warning("API-Abfrage fehlgeschlagen: " + ex.getClass().getSimpleName());
                    }
                    String result = message;
                    Bukkit.getScheduler().runTask(this, () -> {
                        Player online = Bukkit.getPlayer(playerId);
                        if (online != null) online.sendMessage(format(result));
                    });
                });
                return true;
            }
            case "nexora" -> {
                if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
                    sender.sendMessage(format("&fNexoraCore 1.2 | API: " + (api == null ? "&cdeaktiviert" : "&akonfiguriert")));
                    return true;
                }
                if (!sender.hasPermission("nexora.admin")) {
                    sender.sendMessage(format("&cKeine Berechtigung")); return true;
                }
                if (args[0].equalsIgnoreCase("reload")) {
                    reloadConfig(); configureApi(); sender.sendMessage(format("&aKonfiguration neu geladen")); return true;
                }
                if (args[0].equalsIgnoreCase("sync")) {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(format("&cNur im Spiel verfügbar")); return true;
                    }
                    sync(player); sender.sendMessage(format("&eSynchronisierung angefragt; Ergebnis im Serverlog prüfen")); return true;
                }
                sender.sendMessage(format("&e/nexora info | reload | sync")); return true;
            }
            default -> { return false; }
        }
    }
}
