# NexoraCore 3.2

Minecraft-Plugin für Paper **26.3 Build 244** und **Java 25**, mit lokalem Coin-Ledger, Kits, Coin-Item-Shop, optionaler LuckPerms-Verwaltung und HTTPS-Synchronisierung. Keine Echtgeldzahlungen, keine automatische Vergabe von Owner-Rechten anhand eines Spielernamens.


## Shop-Verwaltung 3.2

Neue Shopdaten werden in derselben SQLite-Datenbank ergänzt; vor Schemaänderungen wird ein konsistentes `.pre-shop-<UUID>.bak` angelegt. Bestehende Konten, Ränge, Spielerdateien und `shop.yml`-Angebote werden nicht gelöscht oder neu befüllt. Neue und alte Item-Angebote bleiben unterscheidbar; neue IDs dürfen bestehende Legacy-IDs nicht überdecken. Gelöschte neue IDs bleiben für die Historie reserviert.

Nur Serverkonsole bzw. ausdrücklich berechtigte Owner mit **`nexora.shop.manage`** dürfen Produkte verwalten. Dieses Recht ist ab 3.2 standardmäßig **false**, auch für OP; explizit mit LuckPerms an vertrauenswürdige Owner vergeben. Das bisherige `nexora.admin` allein reicht nicht.

Konsolenbefehle (ohne `/`):

```text
nexora shop create <id> <name mit Leerzeichen> <preis> <coins|eur>
nexora shop list [seite]
nexora shop delete <id>
nexora shop price <id> <preis>
nexora shop sync
nexora shop set <id> <name|description|category> <text>
nexora shop reward <id> <item|custom|kit|rank|coins|cosmetic> [wert]
nexora shop enable <id>
nexora shop disable <id>
nexora shop orders [seite]
nexora shop review <bestell-uuid> <fulfilled|refund>
```

Erstelle Produkte mit deinen tatsächlichen Namen und Preisen. EUR unterstützt bis zu zwei Nachkommastellen (Punkt oder Komma); Coins sind ganze Zahlen. Preise sind serverseitig validiert, nichtnegative exakte Ganzzahlen. Neue Produkte sind zunächst deaktiviert und haben keine erfundene Belohnung.

Belohnungen:

| Art | Wert / Einrichtung |
| --- | --- |
| `rank` | Existierende LuckPerms-Gruppen-ID für VIP-/andere kaufbare Ränge; zusätzlich in `shop.allowed-rank-groups` freigeben |
| `kit` | Bereits wirklich konfiguriertes Kit aus `kits.yml`; Items werden beim Kauf als Snapshot gespeichert |
| `coins` | Positiver ganzer Coin-Betrag; ausschließlich lokale Wirtschaft |
| `cosmetic` | Wirklich existierendes Funktionsrecht, zusätzlich in `shop.allowed-cosmetic-permissions` freigeben; LuckPerms nötig |
| `item` / `custom` | Exakte Kopie des gehaltenen Stapels; Befehl muss im Spiel von berechtigtem Owner ausgeführt werden |

Kosmetikprodukte schalten freigegebene Rechte frei; sie erzeugen keine neuen Partikel-/Haustier-/Skinfunktionen. Die tatsächliche kosmetische Funktion muss vom Server unterstützt werden. Keine Wildcards, keine frei ausführbaren Belohnungs-/Konsolenbefehle, keine geratenen Rangrechte. Nach Belohnungsänderung muss das Produkt erneut aktiviert werden. Beschreibungen und Kategorien separat setzen; Kategorien sind sichere IDs.

Spieler: `/shop` zeigt die erste Seite neuer Produkte und bestehende Gegenstandsangebote, `/shop page <seite>` weitere neue Produkte, `/shop buy <id>` kauft aktive Coin-Produkte. Neue Produkte werden beim Bezahlen nochmals gegen DB-Preis, Version und Aktivierung geprüft. EUR-Käufe benötigen den echten Website-Zahlungsweg und die explizit aktivierte, geprüfte Adapterintegration. Es gibt keinen ungeprüften `paid`-Konsolenbefehl.

Bestellungen speichern Preis, Währung, Produktversion, Ziel-UUID und Belohnung dauerhaft. Dieselbe externe Bestell-ID bzw. Zahlungsreferenz kann keine zweite Belohnung auslösen. Coin-Pakete werden zusammen mit dem Erfüllungsstatus atomar gebucht. Bei Item-/LuckPerms-Abstürzen bleibt die Bestellung zur manuellen Owner-Prüfung offen; keine automatische Wiederholung mit Duplikationsrisiko.

Bestehendes `/nexora shop-add` funktioniert weiter, benötigt aber ebenfalls das explizite Shop-Verwaltungsrecht. Seine `shop.yml`-Angebote bleiben lokale Legacy-Item-Angebote; sie werden nicht stillschweigend als neue Website-Produkte ausgegeben. Erstelle passende neue, getrennte Produkt-IDs, wenn sie auf der Website erscheinen sollen.

**Website/Owner-Panel:** Der neue Adapter ist standardmäßig aus. Automatische Seitensynchronisierung erfolgt nach Konfiguration im Minutentakt. Die tatsächliche Base44-Anpassung und alle Felder/Zustände sind in [docs/SHOP-BASE44.md](docs/SHOP-BASE44.md) spezifiziert. Im Repository ist keine fertige Base44-App enthalten; Website- und Zahlungstests stehen aus. Das Plugin setzt keine nicht belegte vorhandene Shop-API voraus.

## Installation / Update von 1.2 / 2.0

1. Server stoppen. Den gesamten Ordner `plugins/NexoraCore/` und die Spielerwelten/Inventare sichern.
2. Alte NexoraCore-JAR entfernen und nur die neue `NexoraCore-3.2.0.jar` in `plugins/` legen. Java 25 verwenden.
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

Einfache Spielerrechte sind standardmäßig erlaubt. Coin-/Kitverwaltung und bisheriges `nexora.admin` sind standardmäßig OP; Shopverwaltung benötigt ab 3.2 ein explizites Recht. Wartungsänderungen, Rangänderungen, Lieferungsauflösung und Wartungs-Bypass sind standardmäßig **nicht** erlaubt und müssen separat an vertrauenswürdige Personen vergeben werden. Die echte Serverkonsole darf administrative Befehle ausführen. `nexora.admin` allein erteilt keine neuen Owner-Rechte. `nexora.maintenance.bypass` vor Aktivierung der Wartung an zuständige Owner vergeben. Bereits verbundene Spieler werden nicht automatisch gekickt.

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

GitHub Actions führt JUnit-Tests und JAR-Prüfung aus und stellt **NexoraCore-Paper26.3-Java25** sowie **NexoraCore-Testberichte** bereit. Das Plugin liegt unter `build/libs/NexoraCore-3.2.0.jar`. SQLite/Gson sind gebündelt, Paper/LuckPerms-API werden nicht eingebettet. Unter Actions einen erfolgreichen Lauf öffnen, Artefakt laden und ZIP entpacken; ausschließlich die JAR in `plugins/` legen.

Automatisierte Prüfungen und erforderliche Servertests: [docs/TESTS.md](docs/TESTS.md).
