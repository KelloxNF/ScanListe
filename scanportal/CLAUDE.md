# Scan-Portal – Arbeitsregeln

## Dokumentation bei jeder Änderung aktualisieren (Wunsch des Nutzers)
Nach **jeder** Änderung an der App alle drei Dokumentationen im selben Zug anpassen:

1. **Anwender-Anleitung** „Scan-Portal – Dokumentation“ (Claude Docs, Tabs je Kapitel):
   https://claude.ai/code/artifact/3deca317-5e1a-417a-90bd-7f65ad4dcb64
   – betroffene Abschnitte ändern, Versionsnummer im Überblick („Diese Anleitung beschreibt Version …“) hochzählen.
2. **Technisches Handbuch** (HTML, Wo · Was · Wie · Warum): Quelle `scanportal/docs/handbuch.html`,
   veröffentlicht als Artifact https://claude.ai/artifact/7vWsr6UHqh1YgWBs23PQm6
   – Funktionsblatt anpassen/ergänzen, Stand/Version im Kopf und Fußzeile, Versionsgeschichte, Dateitabelle (Zeilen), Testanzahl.
   – Nach dem Ändern die Datei erneut auf dieselbe Artifact-URL veröffentlichen und dem Nutzer als Datei schicken.
3. **README** `scanportal/README.md`.

## Sonstiges
- Version in `app/build.gradle.kts` (versionCode/versionName) bei jeder App-Änderung erhöhen.
- Interner Name `genexis` (JSON-Schlüssel, Variablen) bleibt für Kompatibilität; angezeigt wird „Multidata-QR-Code“.
- Commits enden mit den Attributionszeilen der Sitzung.
