# NexoraCore 3.2: erforderlicher Base44-Adapter

## Status und Grenze

Es gibt im Repository keinen Base44-Quellcode und keinen belegten vorhandenen Shop-API-Vertrag. Daher ruft NexoraCore **keine erfundene Base44-Funktion** auf. Die bisherigen `player.sync`, `coins.get` und `rank.get` bleiben unverändert. Der neue Shop-Adapter ist ein hier ausdrücklich **neu definierter Integrationsvertrag**, standardmäßig deaktiviert. Die tatsächliche Base44-Funktion samt Owner-Panel muss anhand dieser Spezifikation implementiert und getestet werden. Dieses Dokument behauptet keine bereits erfolgte Änderung an der Website.

## Konfiguration nach Backend-Implementierung

In der lokalen Serverdatei `plugins/NexoraCore/config.yml` ergänzen (vorhandene Felder/Token nicht ersetzen):

```yaml
shop:
  allowed-rank-groups: []
  allowed-cosmetic-permissions: []
shop-sync:
  enabled: false
  endpoint: ''
  server-id: ''
  token: ''
  owner-ids: []
  accept-paid-orders: false
```

`endpoint` ist die tatsächliche, vom Betreiber implementierte HTTPS-URL. Kein bestehender Funktionsname wird angenommen. `server-id` muss je Minecraft-Server eindeutig sein. Token bevorzugt über **NEXORA_SHOP_API_TOKEN** als echte Server-Umgebungsvariable setzen; lokales `token` ist nur ein Fallback und bleibt im Repository leer. `owner-ids` enthält verifizierte Backend-Benutzerkennungen, keine geratenen Minecraft-Namen. Erst nach Backendtests `enabled: true`. Euro-Lieferungen zusätzlich erst nach erfolgreicher Zahlungsprüfung mit `accept-paid-orders: true` freischalten. Kein OAuth-/Zahlungsanbieter-Secret gehört ins Plugin oder Browser-Frontend.

## Transport: neu zu implementierender Vertrag `nexora.shop/1`

NexoraCore POSTet JSON an genau die konfigurierte URL. Header: `Content-Type: application/json`, `X-Nexora-Token: <lokales Geheimnis>`. HTTPS ist Pflicht; Redirects werden nicht verfolgt, Token wird nicht geloggt. Request/Response sind auf 64 KiB begrenzt. Der Adapter arbeitet in Seiten von bis zu 10 Produkten und 20 Bestellungen. Große Historien benötigen mehrere Minutentakte; `nexora shop sync` fordert eine weitere Seite an. Es ist kein Echtzeit-Push mit garantierter Latenz.

Anfragefelder:

| Feld | Inhalt |
| --- | --- |
| `protocol` | exakt `nexora.shop/1` |
| `server_id` | konfigurierte Kennung |
| `request_id` | eindeutige UUID dieses Versuchs |
| `products_offset`, `orders_offset` | jeweiliger Seitenoffset |
| `products_last_page`, `orders_last_page` | Hinweis auf Ende der aktuellen Runde |
| `products` | bis zu 10 Produktdatensätze, auch deaktivierte und Löschmarkierungen |
| `orders` | bis zu 20 Bestelldatensätze, nur für serverseitige private Speicherung |
| `acknowledgements` | bis zu 20 Ergebnisse zuvor gelieferter Owner-/Zahlungsaufträge |

Produktdatensatz: `id`, `name`, `price_minor` (Dezimalstring einer Ganzzahl), `currency` (`coins` oder `eur`), `description`, `category`, `type`, `active`, `deleted`, `revision` (Dezimalstring), `purchase_status`. **EUR-Minor-Units sind Cent, Coin-Minor-Units ganze Coins.** Keine Fließkommazahlen verwenden. `purchase_status` enthält `available`, `disabled`, `deleted` oder `payment_integration_required` bzw. `local_economy_required`. Bei Web-Wirtschaft sind lokale Coin-Käufe weiterhin gesperrt, auch wenn ein Produkt aktiv ist; die Website muss diesen konfigurationsabhängigen Zustand berücksichtigen.

Bestelldatensatz: `id`, `buyer_uuid`, `product_id`, `product_revision`, `price_minor`, `currency`, `status`, `created_at`. Keine internen Itembytes oder Zahlungsanbieter-Secrets werden übertragen. UUID und Bestellhistorie sind personenbezogene Daten: nicht öffentlich ausliefern.

Antwort muss `ok` als echtes Boolean `true` und **exakt dieselbe** `request_id` enthalten. Erst nach dauerhaftem Speichern bestätigen. Optional:

- `owner_commands`: maximal 20 Owner-Aufträge.
- `paid_orders`: maximal 20 nachweislich bezahlte EUR-Bestellungen.

Ohne passende Bestätigung bleiben die Offsets unverändert und dieselben Zustände werden später erneut gesendet. Backend muss Upserts nach `(server_id, product_id)` und Revision ausführen, **Löschmarkierungen erhalten**, ältere Produktrevisionen ignorieren und Bestellungen nach ID aktualisieren. Nicht anhand einer unvollständigen Seite andere Produkte löschen. Bei gleichem Bestellstatus idempotent aktualisieren. Produktrevisionen sind monoton; Bestellstatusänderungen derselben ID kommen nacheinander, mit nur einer laufenden Shop-Sync-Anfrage je Server. `server-id` niemals gleichzeitig mehreren Serverinstanzen zuweisen.

### Owner-Aufträge

Erforderlich: `id` (stabile Auftrags-UUID), `owner_id` (serverseitig verifizierte Backend-Benutzerkennung), `operation`, `product_id`. Jede Bearbeitung außer `create` erfordert `expected_revision` als Dezimalstring.

| Operation | Weitere Felder / Wirkung |
| --- | --- |
| `create` | `name`, `price` als Anzeige-Dezimalstring, `currency`; erstellt deaktiviertes Produkt ohne Belohnung |
| `edit` | `name`, `description`, `category`; Metadaten ersetzen |
| `price` | `price` als Dezimalstring, Währung bleibt unverändert |
| `enable` / `disable` | Aktivieren nur mit gültiger vorhandener Belohnung |
| `delete` | Dauerhafte Löschmarkierung, Historie bleibt erhalten |

Belohnungskonfiguration wird bewusst am Minecraft-Server vorgenommen (`nexora shop reward`). Owner-Panel kann Produkte erstellen, Metadaten/Preise bearbeiten und konfigurierte Produkte aktivieren/deaktivieren. Es gibt keine generische Remote-Konsole oder frei ausführbare Belohnungsbefehle. Website-Rollen dürfen nicht aus einem vom Browser gesetzten `owner_id` abgeleitet werden.

Erfolgreiche Aufträge werden mit kanonischer, unverändert wiederholter JSON-Repräsentation in derselben DB-Transaktion wie die Produktänderung gespeichert. Dieselbe ID mit anderem Inhalt/anderem Owner wird abgelehnt. Wiederholungen dürfen das Produkt nicht erneut ändern. Antwort im nächsten Sync: Ack mit `kind: owner_command`, `id`, `result: applied` oder `rejected` und generischem Grund. Bei Ablehnung nach Korrektur **neue** Auftrags-ID verwenden, erwartete Revision neu laden. Backend darf einen Fehler nicht als Erfolg anzeigen.

### Bezahlte EUR-Bestellungen

Erforderliche Felder im neuen Adaptervertrag:

- `id`: unveränderliche Bestell-UUID.
- `buyer_uuid`: verifizierte Minecraft-/Floodgate-UUID eines bereits auf dem Server bekannten Spielers.
- `product_id`, `revision`, `price_minor` (Ganzzahlstring), `currency: eur`.
- `payment_status: paid`.
- `payment_reference`: eindeutige Anbieter-/Zahlungsreferenz, Zeichen `a-z A-Z 0-9 _ : . -`, höchstens 160 Zeichen.

**`paid` allein ist kein Zahlungsnachweis.** Das Base44-Backend muss zunächst den echten Anbieter-WebHook samt Signatur verifizieren bzw. den Anbieter serverseitig abfragen. Händlerkonto, tatsächlichen Betrag, Währung, Zahlungsempfänger, Status und zugehörige gespeicherte Bestellung prüfen. Erst dann im authentifizierten Adapterantwortkanal an das Plugin liefern. Browser-Redirects, Query-Parameter und clientseitige Erfolgsmeldungen dürfen niemals ausreichen. Annahme im Plugin ist standardmäßig aus.

Plugin prüft Preis, Währung, Produktrevision, Aktivierung, Belohnung, bekannte Ziel-UUID und eindeutige Zahlungsreferenz nochmals gegen die eigene DB. Manipulierte Bestellpreise und doppelte Referenzen werden abgewiesen. Ein Wiederholungsauftrag derselben ID liefert nur den gespeicherten Status. Wurde ein Produkt nach Zahlung geändert/deaktiviert, erfolgt **keine** stillschweigende Lieferung eines anderen Produkts: Backend muss ablehnen/prüfen und gegebenenfalls den echten Zahlungsanbieter-Erstattungsweg auslösen.

Website-Coin-Bestellungen werden in dieser Version nicht aus dem Browser an den Adapter zur Abbuchung geschickt. Coin-Käufe erfolgen mit `/shop buy <id>` autoritativ im Minecraft-Server. Eine spätere Website-Coin-Kaufroute erfordert einen separat abgesicherten, idempotenten Abbuchungsvertrag; sie wird nicht vorgetäuscht.

## Zustände und Doppellieferung

- `ready`: dauerhaft angenommene Bestellung, noch nicht ausgegeben. Offline-Itembestellungen warten auf Join; voller Inventarplatz verhindert Ausgabe. Wiederholte Prüfung im Minutentakt für Online-Spieler.
- `delivering`: externe Ausgabe begonnen; nach Absturz **keine automatische Wiederholung**. Owner muss Inventar/LuckPerms prüfen.
- `fulfilled`: Ausgabe dauerhaft protokolliert. Coin-Pakete werden atomar mit diesem Status gebucht.
- `refunded`: lokale Coin-Zahlung einmalig zurückerstattet.
- `refund_required`: EUR-Lieferung verworfen; **keine** echte Anbieter-Erstattung behauptet. Erstattung muss außerhalb des Plugins beim echten Zahlungsanbieter erfolgen.

Keine verteilte Transaktion kann SQLite, Minecraft-Inventare, LuckPerms und Zahlungsanbieter atomar speichern. Unklare externe Lieferungen bleiben daher für manuelle Prüfung gesperrt. Item-Lieferungen: `/nexora pending` und `/nexora resolve`. Rang-/Kosmetik-/noch nicht ausgegebene Bestellungen: `/nexora shop review`. Erstattung nur nach tatsächlicher Prüfung der bereits erhaltenen Belohnung.

## Konkrete Base44-Arbeiten / Owner-Panel

1. Privaten serverseitigen Adapter nach obigem **neuen** Vertrag erstellen, Token sicher aus Secrets lesen und server-id daran binden. Request validieren und Größen-/Ratenlimits setzen. Keine unbestätigten Funktionsnamen aus dieser Anleitung übernehmen.
2. Produkt-, Bestell-, Owner-Auftrags- und Zahlungsbeleg-Datenhaltung mit eindeutigen Indizes auf Server/Produkt, Auftrags-ID, Bestell-ID und Zahlungsreferenz einrichten. Backend-Transaktionen bzw. sichere Compare-and-Swap-Verfahren prüfen; keine nur clientseitigen Eindeutigkeitsprüfungen.
3. Öffentliche Produktansicht liefert ausschließlich aktive, nicht gelöschte Produkt-Metadaten mit korrekter EUR-Cent-Konvertierung, Name, Beschreibung, Kategorie und Kauf-/Integrationsstatus. Browsertexte sicher rendern, kein ungeprüftes HTML.
4. Authentifiziertes Owner-Panel mit **serverseitiger** Owner-Prüfung: Erstellen/Bearbeiten/Preis/Deaktivieren als persistente Aufträge vormerken. UI zeigt `ausstehend`, `angewendet` oder `abgewiesen` gemäß Plugin-Bestätigung. Nie einen Browserwert als bereits bestätigte Produktänderung darstellen.
5. Bestellübersicht nur für Owner; normale Nutzer dürfen ausschließlich ihre nach verifizierter Minecraft-Verknüpfung zugeordneten Bestellungen sehen. Öffentliche API darf keine UUID-Liste, Tokens oder fremde Kaufhistorie ausgeben.
6. Echten Zahlungsanbieter einbinden und WebHooks testen. Erst danach akzeptierte EUR-Bestellungen an den Adapter schicken und `accept-paid-orders` lokal aktivieren. Keine automatische Website-Owner-/Admin-Rangvergabe aus Produktkäufen.
7. Die lokale Wirtschaft bleibt lokal. V2-Website-Guthaben nicht überschreiben; Coin-Pakete aus EUR-Zahlungen funktionieren nur bei `economy.mode: local`. Vor Umstellung reale Salden gesichert übernehmen.
8. End-to-End-Tests: echte Produktänderung → Website, Ownerauftrag → Plugin → Ack, falscher Token, falscher Owner, falscher Preis, doppelte Zahlung, API-Ausfall/Neustart, Offline-Spieler, volle Inventare, Absturz und manuelle Prüfung. Erst danach live schalten.

Es wurde keine Base44-Website veröffentlicht, kein Zahlungsanbieter aufgerufen und kein Live-Zahlungsvorgang ausgeführt.
