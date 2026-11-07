# NexoraCore 2.0

Minecraft-Plugin für Paper **26.3 Build 244** und **Java 25**, mit lokalem Coin-Ledger, Kits, Coin-Item-Shop, optionaler LuckPerms-Verwaltung und HTTPS-Synchronisierung. Keine Echtgeldzahlungen, keine automatische Vergabe von Owner-Rechten anhand eines Spielernamens.

## Installation / Update von 1.2

1. Server stoppen. Den gesamten Ordner `plugins/NexoraCore/` und die Spielerwelten/Inventare sichern.
2. Alte NexoraCore-JAR entfernen und nur die neue `NexoraCore-2.0.0.jar` in `plugins/` legen. Java 25 verwenden.
3. Server starten. `players.yml` und vorhandene `config.yml` werden nicht migriert oder überschrieben. NexoraCore erstellt eine Originalkopie der alten Spielerdatei und eine separate SQLite-Datenbank `nexora.db`. Ungültige YAML-Dateien stoppen den Pluginstart statt Daten zurückzusetzen.
4. LuckPerms separat installieren, falls Ränge genutzt werden. Geyser/Floodgate bleiben separate Plugins. Keine Minecraft-/Website-Identitäten oder Rechte werden geraten.
5. `/nexora info` und `/nexora status` prüfen. API „konfiguriert“ bedeutet nicht, dass ein erfolgreicher API-Login/Sync nachgewiesen ist.

### Coins ohne Datenverlust

**Vorhandene 1.2-Konfiguration ohne `economy.mode`:** `/coins` liest weiter `coins.get` von der Website. `/coins web` bleibt immer verfügbar. Lokale Konten sind getrennt; Shop/Überweisungen/administrative Coin-Änderungen sind im Web-Modus gesperrt. Es werden keine Website-Guthaben importiert oder überschrieben.

**Neue Installation:** `economy.mode: local` ist voreingestellt. Neue lokale Konten beginnen ausdrücklich bei 0; Join-Namen stammen aus tatsächlichen Serververbindungen. `/coins local` zeigt immer den lokalen Saldo.

Wer von Website-Coins auf lokale Coins wechseln will, muss zuerst den tatsächlichen autoritativen Kontostand und den Migrationszeitpunkt festlegen, Website-Schreibvorgänge sperren und die realen Salden kontrolliert über `/coins set <UUID> <Betrag>` übernehmen. Erst danach `economy.mode: local` setzen und `/nexora reload` ausführen. Keine automatische oder bidirektionale Guthaben-Migration. UUIDs bleiben unverändert, einschließlich Floodgate-UUIDs.

## Befehle und Rechte

| Befehl | Funktion | Berechtigung |
| --- | --- | --- |
| `/coins [local\|web]` | Eigenes lokales/Web-Guthaben | `nexora.coins` |
| `/pay <Online-Name\|UUID> <Betrag>` | Lokale Überweisung, nur positiv | `nexora.pay` |
| `/coins set\|add\|take <Online-Name\|UUID> <Betrag>` | Lokale Coins administrieren | `nexora.coins.admin` |
| `/kit [id]` | Liste / Kit abholen | `nexora.kit` und für Bezug `nexora.kit.<id>` |
| `/shop` / `/shop buy <id>` | Angebote / Kauf | `nexora.shop`, ggf. Angebotsrecht |
| `/rank [web]` | LuckPerms-Hauptgruppe, sonst Website-Rang | `nexora.rank` |
| `/nexora info` | Tatsächlicher Konfigurationsstatus | öffentlich |
| `/nexora reload` / `sync` | Konfiguration laden / eigenen Sync vormerken | `nexora.admin` |
| `/nexora kit-save <neue-id> <Cooldown-Sekunden>` | Aktuelles Lagerinventar als Kit speichern | `nexora.kits.manage` |
| `/nexora shop-add <neue-id> <Coin-Preis>` | Stapel in Haupthand als Angebot speichern | `nexora.shop.manage` |
| `/nexora rank add\|remove <Online-Name\|UUID> <Gruppe>` | LuckPerms-Gruppenvererbung ändern | `nexora.ranks.manage` + `nexora.ranks.group.<Gruppe>` + Konfigurationsfreigabe |
| `/nexora status` | Wartung, echte Onlinezahl, Queue/Lieferungen | `nexora.owner.status` |
| `/nexora maintenance on\|off` | Neue Verbindungen sperren/freigeben | `nexora.owner.maintenance` |
| `/nexora backup` | Konsistentes SQLite-Backup | `nexora.owner.backup` |
| `/nexora audit` | Letzte zehn protokollierte Vorgänge | `nexora.owner.audit` |
| `/nexora pending` | Erste 50 ungeklärte Lieferungen anzeigen | `nexora.owner.deliveries` |
| `/nexora resolve <Transaktions-UUID> delivered\|refund` | Geprüfte Lieferung abschließen/erstatten | `nexora.owner.deliveries` |

Einfache Spielerrechte sind standardmäßig erlaubt. Coin-/Katalogverwaltung und bisheriges `nexora.admin` sind standardmäßig OP. Wartungsänderungen, Rangänderungen, Lieferungsauflösung und Wartungs-Bypass sind standardmäßig **nicht** erlaubt und müssen separat an vertrauenswürdige Personen vergeben werden. Die echte Serverkonsole darf administrative Befehle ausführen. `nexora.admin` allein erteilt keine neuen Owner-Rechte. `nexora.maintenance.bypass` vor Aktivierung der Wartung an zuständige Owner vergeben. Bereits verbundene Spieler werden nicht automatisch gekickt.

Online-Namen werden nur aus aktuellen Verbindungen aufgelöst; für offline bekannte Spieler UUID verwenden. Es gibt keine geratene Offline-UUID anhand eines Namens.

## Echte Kits, Shop-Angebote, Ränge

Kits/Shop enthalten absichtlich keine erfundenen Preise oder Inhalte. Lege sie mit den obigen Befehlen aus tatsächlichen Gegenständen an. Kit-Inhalte kopieren nur das Lagerinventar, ohne Rüstung/Offhand. Bereits vorhandene IDs werden durch Befehle nicht überschrieben. Dateien `kits.yml`/`shop.yml` bei gestopptem Server bearbeiten oder danach neu laden. Shop-Angebote können optional ein `permission`-Feld haben. Kits benötigen immer ihr einzelnes Kit-Recht.

Kits und Käufe planen die komplette Inventaränderung vorab. Fehlender Platz, fehlende Rechte, negatives Guthaben und offene ungeklärte Lieferungen verhindern die Ausgabe. Cooldowns gelten je Spieler-UUID und Kit und bleiben über Neustarts erhalten. Geldwerte sind ganze, nichtnegative 64-Bit-Zahlen; Überläufe werden abgewiesen. Lokale Überweisungen werden atomar mit Audit gespeichert.

LuckPerms-Gruppen müssen tatsächlich existieren und unter `ranks.manageable-groups` ausdrücklich freigegeben sein. NexoraCore fügt direkte, dauerhafte Gruppenvererbungen hinzu bzw. entfernt sie. Andere Gruppen, kontextbezogene/temporäre Vererbungen und bestehende Rechte werden nicht pauschal gelöscht; der LuckPerms-Primärgruppenmodus entscheidet weiter über `/rank`. Keine automatische Owner-Zuweisung und keine Web-Rangantwort wird als Berechtigung übernommen.

## Ausfall- und Lieferungsbehandlung

SQLite verwendet WAL und `synchronous=FULL`. Kontostände, Cooldowns und Reservierungen werden gemeinsam gespeichert. Spielerinventar und SQLite können technisch nicht in einer gemeinsamen Transaktion gespeichert werden. Eine vor der Ausgabe reservierte Lieferung bleibt bei unklarem Ausgang deshalb `pending` und sperrt weitere Kit-/Shop-Lieferungen für diesen Spieler. **Keine automatische Wiederholung oder Erstattung nach Absturz.**

Owner prüft tatsächliches Inventar/Spielerdaten und Audit, dann `resolve ... delivered` oder `refund`. Refund erstattet genau einmal und stellt bei Kits den vorherigen Cooldown wieder her. Das ist ein bewusster manueller Abgleich, keine Behauptung vollständiger Absturz-Atomizität. Vor Produktion Crash-Szenarien auf Testserver prüfen. Lokale SQLite-Schreibvorgänge laufen serialisiert auf dem Serverthread; für große Netzwerke ist Lastmessung nötig. Eine Datenbank gehört genau einer Serverinstanz.

Backups bei Start und auf Befehl sind konsistente separate DB-Dateien. Diese regelmäßig außerhalb des Servers sichern/rotieren; `config.yml`, Kataloge, LuckPerms-Daten und Spielerinventare zusätzlich sichern. Restore nur bei gestopptem Server und zusammenpassenden Spieler-/DB-Ständen. Rollback auf v1 nutzt die erhaltene `players.yml`, übernimmt aber keine neuen v2-Funktionen automatisch.

## Base44 / Geheimnisse

Siehe [docs/BASE44.md](docs/BASE44.md). Vorhandene `player.sync`, `coins.get`, `rank.get` bleiben erhalten. Neue Snapshots sind explizit opt-in. Der echte Base44-Backendcode liegt nicht in diesem Repository und wurde hier nicht verändert oder als funktionsfähig behauptet.

API-Token ausschließlich als Server-Umgebungsvariable `NEXORA_API_TOKEN` oder in der lokalen Serverkonfiguration hinterlegen. Ein wörtlicher Platzhalter `NEXORA_API_TOKEN` wird abgelehnt. Token nie in GitHub, Issue, Chat oder Build-Parameter eintragen. Default-Token bleibt leer; CI benötigt keine eigenen Secrets. Falls zuvor ein Platzhalter als echtes gemeinsames Geheimnis verwendet wurde, vor Aktivierung einen echten zufälligen Token auf beiden Seiten lokal setzen.

## Build, Artefakte, Tests

Temurin **25**, Gradle **9.2.1**: `gradle --no-daemon clean build`.

GitHub Actions führt JUnit-Tests und JAR-Prüfung aus und stellt **NexoraCore-Paper26.3-Java25** sowie **NexoraCore-Testberichte** bereit. Das Plugin liegt unter `build/libs/NexoraCore-2.0.0.jar`. SQLite/Gson sind gebündelt, Paper/LuckPerms-API werden nicht eingebettet. Unter Actions einen erfolgreichen Lauf öffnen, Artefakt laden und ZIP entpacken; ausschließlich die JAR in `plugins/` legen.

Automatisierte Prüfungen und erforderliche Servertests: [docs/TESTS.md](docs/TESTS.md).
