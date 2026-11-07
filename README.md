# NexoraCore 1.2

Ziel: Paper 26.3 Build 244, Java 25.

## Enthalten
- Asynchrone HTTPS-Anfragen an `nexoraApi`
- `player.sync` beim Join (UUID, Name, Plattform, Floodgate-XUID, falls ermittelbar)
- `/coins` und `/rank` lesen echte API-Werte (keine erfundenen Werte)
- `/nexora info`, `/nexora reload`, `/nexora sync`

## Build
Voraussetzungen: JDK 25 und Gradle 9.2.1. Im Projektordner:

```sh
gradle --no-daemon clean build
```

Ergebnis: `build/libs/NexoraCore-1.2.0.jar`. Die Paper-API wird nur zum Kompilieren verwendet; Gson ist im JAR enthalten.

## GitHub Actions und Download

Bei Pushes, Pull Requests und manuellem Start unter **Actions → Build NexoraCore → Run workflow** wird mit Temurin Java 25 und Gradle 9.2.1 gebaut. Der Workflow prüft die JAR-Struktur, Plugin-Metadaten und Java-25-Bytecode. Nur bei Erfolg wird das Artefakt **NexoraCore-Paper26.3-Java25** für 14 Tage bereitgestellt.

Öffne einen erfolgreichen Lauf unter **Actions**, lade das Artefakt herunter und entpacke das ZIP. Installiere die darin enthaltene **NexoraCore-1.2.0.jar** im Serverordner `plugins/`. Das ZIP und der Quellcode gehören nicht in den Pluginordner.

Der Build benötigt keine eigenen Tokens, Passwörter oder Repository-Secrets. Website-Zugangsdaten ausschließlich auf dem Minecraft-Server in `plugins/NexoraCore/config.yml` hinterlegen. Serverdaten, `.env` und Buildausgaben sind in `.gitignore` ausgeschlossen.

## Einrichtung
JAR in den `plugins`-Ordner legen, Server starten, `plugins/NexoraCore/config.yml` bearbeiten. Nur einen gültigen serverseitigen Token dort eintragen, `website-sync.enabled: true` setzen und Server neustarten. Token nie teilen.

## Noch nicht implementiert / geprüft
- Ein erfolgreicher Actions-Lauf bestätigt die Kompilierung; ein Live-Test auf dem eigenen Paper-26.3-Server ist zusätzlich erforderlich.
- Kein eigener Coin-Shop, keine Kits, keine LuckPerms-Rangübernahme, keine automatische Website-Rangvergabe
- Keine vollständige Spielzeit-Übertragung
- Bedrock-Erkennung nur bei erreichbarer Floodgate-API; Bedrock-Port separat prüfen
- API-URL/Antwortformate müssen im Livebetrieb bestätigt werden

Bitte zunächst auf Testserver mit Backup installieren.
