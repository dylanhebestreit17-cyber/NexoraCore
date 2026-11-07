# Base44-Protokoll und Integrationsgrenze

Die vorhandene API liegt außerhalb dieses Repositories. Ohne ihren Backendcode und gültige lokale Server-Zugangsdaten kann hier weder ihr Antwortvertrag noch ihr Livebetrieb bestätigt werden. Diese Dokumentation ist ein Integrationsvertrag, kein Bericht über bereits umgesetzte Base44-Änderungen.

## Bestehende Aufrufe (Version 1, Standard)

POST an die konfigurierte HTTPS-URL, Header `Content-Type: application/json`, `X-Nexora-Token: <lokales Geheimnis>`.

- `player.sync`: `uuid`, tatsächlicher `name`, `platform` (`JAVA`, `BEDROCK`, bei fehlender verifizierbarer Edition `UNKNOWN`); `xuid` nur nach erfolgreicher Floodgate-API-Erkennung. Bestätigung ausschließlich JSON `{"ok":true}`. Fehlende Floodgate-Verifikation überspringt den Sync; Namen/UUID-Präfixe sind kein Identitätsbeweis.
- `coins.get`: Spieler-UUID. Erwartet `{"ok":true,"data":{"coins":<nichtnegative ganze Zahl>}}`.
- `rank.get`: Spieler-UUID. Erwartet `{"ok":true,"data":{"rank":"<Rang-ID>"}}`.

`coins.get`/`rank.get` werden nur angezeigt und niemals in die lokale Wirtschaft oder LuckPerms importiert. Eine fehlgeschlagene Anfrage liefert eine Fehlermeldung statt erfundener Werte. `platform` ist eine Information der eigenen Serververbindung, keine alleinige Microsoft-Verifikation für einen Website-Login. Bei Offline-Mode/Proxy ohne erreichbare Floodgate-API wird keine Edition geraten.

## Opt-in Snapshot-Protokoll 2

Nur nach Umsetzung und Prüfung des Backends `website-sync.snapshot-v2: true` setzen und eine eindeutige `website-sync.server-id` vergeben. Der Snapshot enthält:

| Feld | Bedeutung |
| --- | --- |
| `action` | `player.snapshot` |
| `protocol` | `2` |
| `server_id` | Konfigurierte, eindeutige Serverkennung |
| `request_id` | Zufällige, persistent vorgemerkte UUID für Idempotenz |
| `sequence` | Monotoner Zähler aus derselben persistenten SQLite-Datenbank |
| `uuid`, `name`, `platform`, ggf. `xuid` | Tatsächlich bekannte Spieleridentität |
| `local_coins` | Nur im lokalen Wirtschaftsmodus; Snapshot des lokalen Saldos |
| `luckperms_primary_group` | Nur bei tatsächlich geladenem LuckPerms-Nutzer |

Bestätigung: `{"ok":true,"request_id":"<genau die empfangene UUID>"}`. Falsche/fehlende Bestätigung behält den Eintrag. Keine vollständigen Antworten, Tokens oder Endpoint-URLs werden geloggt.

### Backend-Anforderungen vor Freischaltung

1. Geheimnis aus Base44-Server-Secrets laden, Header serverseitig prüfen; niemals im Frontend prüfen oder an Browser senden. Fehlende/falsche Tokens ablehnen. Token an erlaubte `server_id` binden. Die konkrete Base44-Secret-Konfiguration ist separat zu prüfen.
2. Nur bekannte Actions, gültige UUIDs, Ganzzahlen und begrenzte Strings akzeptieren. Keine frei ausführbaren Minecraft-Befehle annehmen.
3. Je `server_id` und Spieler-UUID `sequence` speichern. Nur neuere Snapshots übernehmen, ältere/gleiche ohne erneute Effekte bestätigen. `request_id` idempotent verarbeiten; Ack erst nach dauerhaftem Commit senden.
4. `local_coins` ausschließlich als **separaten Spiegel** speichern. Bestehendes Website-Guthaben nicht überschreiben und Snapshot niemals als Coin-Delta addieren. Kein Browser darf denselben Spiegel als autoritative Wirtschaft verändern.
5. LuckPerms-Rang nur spiegeln; aus Website-Daten werden keine Minecraft-Rechte vergeben. Website-Owner-Rechte weiterhin separat mit sicherer Kontoidentität autorisieren.
6. Eine Serverkennung gehört genau einer DB/Serverinstanz. Nach Restore eines älteren Backups Sequenzkonflikte koordinieren bzw. neue Serverkennung verwenden. Keine zwei Instanzen parallel mit derselben Kennung betreiben.

## Transport und Wiederholung

HTTPS ohne URL-Zugangsdaten/Query/Fragment, keine Redirects, Request-Timeout bis maximal 30 Sekunden, Connect-Timeout maximal 15 Sekunden, begrenzte JSON-Antwortgröße 64 KiB, keine automatischen Geheimnis-Logs. Der Token kommt bevorzugt aus der Server-Umgebung `NEXORA_API_TOKEN`. Der Konfigurationswert bleibt für v1-kompatible lokale Einrichtungen als Fallback erhalten.

Persistente SQLite-Outbox: neuester Zustand pro Spieler ersetzt ältere ausstehende Snapshots. Bereits laufende ältere Bestätigungen löschen keine neueren Snapshots. Versand in begrenzten Batches, zwei HTTP-Worker und begrenzte Task-Warteschlange; Wiederholung beim nächsten Sync/Minutentakt, keine enge Retry-Schleife. Bei deaktiviertem Sync werden keine neuen Einträge erzeugt. Bei aktivierter Integration, aber vorübergehend fehlendem Token bleiben vorgemerkte Einträge lokal erhalten.

Im v1-Modus kann die API nur `ok` bestätigen; v2 bietet zusätzlich explizite Request-IDs/Sequenzen. Das Umschalten der Protokollversion sollte bei leerer Queue und gesichertem Backend erfolgen. Der Ownerstatus unterscheidet Konfiguration und ausstehende Übertragungen; „konfiguriert“ bedeutet nicht „am Backend erfolgreich authentifiziert“.

Liveprüfung: falscher Token → Ablehnung; gültiger Token → echte Bestätigung; API offline → Queue bleibt; Neustart → Queue bleibt; doppelte/ältere Snapshots → keine doppelte Coin-Gutschrift; Website-Saldo und lokale Coins bleiben getrennt. Diese Prüfungen benötigen den echten Base44-Endpunkt und bleiben Server-/Backendtests.
