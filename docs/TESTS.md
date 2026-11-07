# Verifikation und Freigabe auf dem Minecraft-Server

## Automatisch im Actions-Build

JUnit prüft mit temporären SQLite-Datenbanken und synthetischen Test-UUIDs (keine Live-Spielerdaten):

- Kontenstart, erneuter Join ohne Saldoreset, lokale Überweisungen und Summentreue.
- Ungenügendes Guthaben, negative/Null-/Selbstüberweisungen, unbekannte Konten, 64-Bit-Überlauf und Rollback bei fehlschlagendem Audit.
- Gleichzeitige Überweisungen ohne Überziehen.
- Lieferungsreservierung, Sperre weiterer Lieferungen, Abschluss, einmalige Rückerstattung.
- Kit-Cooldown, Erstattung des vorherigen Cooldowns, Überlauf bei Cooldown und Rollback.
- Neustart mit erhaltenem Saldo, offenen Lieferungen, Cooldown und Sequenz.
- Konsistentes Backup inkl. WAL-Daten, Outbox-Update und veraltete Bestätigung ohne Verlust neuer Snapshots.
- HTTPS-URL-/Token-Prüfung, Ablehnung des bekannten Platzhalters, strikte Coin-/Rangwerte, echtes boolesches `ok`, ungültiges JSON, Größenbegrenzung und begrenzter Body-Subscriber.

CI prüft außerdem JAR-Integrität, Hauptklasse, Java-25-Bytecode, Version/API-Metadaten, SQLite/Gson und das Nicht-Einbetten von Paper/LuckPerms-API. Diese Tests simulieren keinen laufenden Minecraft-Server und führen keinen Aufruf mit echten API-Zugangsdaten aus.

## Vor dem Liveeinsatz auf separatem Paper-26.3-Testserver

- [ ] Vollständiges Backup von Pluginordner, LuckPerms, Spielerinventaren/Welten. Genau eine NexoraCore-JAR, Java 25, Plugin lädt ohne Fehler.
- [ ] v1-Update mit vorhandenen Spielernamen/last_seen und bestehender config: Datei unverändert, Originalkopie vorhanden, `/coins` bleibt im Web-Modus. Kein Website-Guthaben wird als lokales Guthaben geraten.
- [ ] Ungültige YAML absichtlich testen: Plugin bricht Start ab und erhält Datei; keine stillen Resets.
- [ ] Mit zwei echten Testkonten lokale Coin-Set/Add/Take/Pay prüfen, zu wenig Coins, großer Betrag, Neustart, richtige UUID (auch Floodgate) und reale Salden kontrollieren.
- [ ] Ohne Verwaltungsrechte jeden administrativen Befehl versuchen: Ablehnung, keine Datenänderung. Auch OP ohne explizite Owner-Rang-/Wartungs-/Lieferungsrechte testen. Rechte nur an sichere Owner-UUIDs vergeben.
- [ ] Echte Kits aus Inventar erstellen, einzelnes Kit-Recht vergeben, Item-Metadaten/Rüstung/Offhand/Stacklimits/vollen Inventarplatz prüfen. Cooldown auch nach Neustart und mit wiederholten schnellen Befehlen testen.
- [ ] Shop mit selbst festgelegtem tatsächlichem Preis erstellen. Kauf prüft vollständige Menge und Saldo; kein Itemverlust, keine Doppelbelastung, keine Drops bei Platzmangel. Kein Kauf im Web-Modus.
- [ ] Während Ausgabe Prozess abrupt beenden. Nach Neustart `pending` und tatsächliches Inventar vergleichen. Manuelle `delivered`-/`refund`-Auflösung, genau einmalige Erstattung und wieder freigegebene Lieferungen prüfen.
- [ ] LuckPerms optional abwesend: Plugin startet, Rangverwaltung erklärt fehlende Abhängigkeit. Mit LuckPerms reale existierende Gruppen freigeben, pro Gruppe Rechte testen, add/remove und Erhalt anderer/temporärer Gruppen prüfen. `/rank` zeigt von LuckPerms ermittelte Hauptgruppe.
- [ ] Wartungs-Bypass vorher setzen; on/off hält über Neustart, neue Spieler ohne Recht abgewiesen, erlaubte Owner kommen rein, bereits verbundene Spieler bleiben verbunden.
- [ ] Datenbank-Backup bei aktivem Betrieb erstellen, auf Kopie unter gestopptem Testserver zusammen mit passenden Inventaren wiederherstellen. Backups rotieren; Sequenz nach Restore mit Backend abstimmen.
- [ ] Base44-Livetests nach `BASE44.md`: gültiger/ungültiger Token, Schema, Timeout, Backendausfall, Retry nach Neustart, Idempotenz und getrennte Guthaben. Erst nach diesen Prüfungen v2-Snapshots aktivieren.
- [ ] Geyser/Floodgate-Spieler auf echtem Java-/Bedrock-/Proxy-Setup testen; ohne verifizierbare Edition UNKNOWN, kein Namenspräfix als Identitätsbeweis.
- [ ] Lasttest mit realistischem Inventar/Katalog/Joinaufkommen, DB-Schreibzeiten und Tickzeiten messen. Das Plugin wurde hier nicht als 1000-Spieler-Netzwerk lastgetestet.

Offene Grenzen: kein Echtgeld-/PayPal-Shop, kein automatischer Web→Minecraft-Rechteimport, keine automatische Website-Coinmigration, keine gemeinsame DB-/Inventartransaktion. Keine Behauptung eines durchgeführten Live-Servertests.


## Zusätzliche Shop-3.2-Prüfungen

Automatisiert: exakte Cent-/Coin-Preise, ungültige/überlaufende Preise, sichere IDs/Texte, deaktivierte neue Produkte, Aktivierung ohne Belohnung, Versionskonflikte, Löschmarkierungen/Historie, unzureichendes Guthaben, manipulierte Preise/Belohnungen, doppelte Bestell-IDs, Zahlungsreferenzen, genau einmalige Coin-Pakete, Claim/Absturzstatus, Inventarjournal/Erstattung, keine lokalen Coins bei EUR-Erstattung, idempotente Owner-Aufträge und Rollback, wartende Bestellungen hinter mehr als 100 Historieneinträgen, Neustart und Schema-1-Migration mit Backup.

Erforderliche reale Tests:

- [ ] Alle fünf verlangten Konsolenbefehle mit echten eigenen Produkten ausprobieren, inklusive Leerzeichen im Namen, EUR-Cent und Coin-Ganzzahlen.
- [ ] OP ohne `nexora.shop.manage` ablehnen; nur sichere Owner-UUID explizit berechtigen. Alte Coin-/Rangdaten vor/nach Migration vergleichen; `.pre-shop`-Backup prüfen.
- [ ] Jede tatsächliche Belohnungsart, Item-Metadaten, volle Inventare, Offline-Bestellungen und LuckPerms-Vererbung auf Paper 26.3 testen.
- [ ] API-Adapter gemäß SHOP-BASE44.md erst implementieren: Produktnamen/Preis/Beschreibung/Kategorie/Status wirklich auf Website prüfen. Owner-Panel darf fremde Nutzer und Browser-Manipulationen ablehnen; Bestellungen nur autorisiert sichtbar.
- [ ] Tatsächliche Zahlungsanbieter-WebHooks und verifizierte Bestellungen testen; falsche Beträge, alte Revision, fremde UUID und wiederholte Zahlungsreferenzen dürfen nichts ausgeben. EUR-Annahme bis dahin deaktiviert lassen.
- [ ] Absturz zwischen Coin-Reservierung, Inventar/LuckPerms und Abschluss testen. `delivering` benötigt manuelle Prüfung; `refund_required` erfordert echten Anbieterrefund, keine automatisch behauptete Rückzahlung.
- [ ] Große Kataloge/Bestellhistorien: Seiten vollständig über mehrere Minutentakte, Löschmarkierungen und wiederholte Owner-Aufträge nach Neustart prüfen. Keine Echtzeit- oder 1000-Spieler-Lastgarantie.
