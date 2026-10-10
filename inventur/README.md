# Inventur – App für das Zebra TC22

Jahresinventur je Kunde direkt am Gerät: Systembestand importieren, mit dem Scanner zählen,
Differenzen sehen, Anschreiben und Zählliste als PDF erzeugen. Läuft komplett offline.

## Installieren
1. Auf GitHub unter **Releases → „Inventur-App – neueste Version“** die Datei `Inventur.apk` laden
   (direkt auf dem TC22 oder per USB/MDM übertragen).
2. Antippen und installieren (ggf. „Installation aus unbekannten Quellen“ erlauben).
3. Einmal **⋮ → Einstellungen**: Absenderzeile, Name der Geschäftsführung, Fußzeile, Zählbereiche (Standard A, B, C).

## Ablauf je Kunde
1. **Neue Inventur**: Kunde (bekannte Kunden werden vorgeschlagen), Stichtag, Zähler, Schreiber, Zählort.
   **Systembestand importieren**: Excel- (.xlsx) oder CSV-Export aus dem Lagersystem mit den Spalten
   *Artikelnummer, Artikel, Bestand, Bestand in Technik*. Die Reihenfolge der Spalten ist egal.
2. **Zählen**: oben den Bereich (A/B/C) wählen, Artikel scannen oder antippen, Menge eingeben.
   - „Jeder Scan = 1 Stück“ anhaken, wenn jedes Gerät einzeln gescannt wird.
   - Unbekannter Barcode (z. B. EAN statt Artikelnummer): einmal dem Artikel zuordnen – die App merkt es sich je Kunde.
     Haben Neu- und Refware dieselbe EAN, beide ankreuzen; die App fragt dann bei jedem Scan nach.
   - Langes Tippen auf einen Artikel (oder „Ändern“ im Mengen-Fenster): **Bezeichnung umbenennen**, Mengen je Bereich
     korrigieren, „geschätzt“ markieren, Bemerkung. Die Artikelnummer bleibt; die neue Bezeichnung merkt sich die App
     je Kunde und verwendet sie auch bei der nächsten Inventur.
   - ⋮ → „Letzte Zählung rückgängig“.
   - Grün = stimmt, orange = Differenz. Bei **Blindzählung** wird der Sollbestand ausgeblendet.
   - Uhrzeit von/bis wird automatisch aus der ersten und letzten Zählung eingetragen.
3. **Ergebnis & Dokumente**: Differenzen prüfen, dann
   - **Anschreiben (PDF)** – je Artikel eine Zeile mit der vollen Bezeichnung aus der App und der gezählten Menge,
     Unterschriftsblock. Artikel ohne Bestand entfallen, der Technik-Bestand erscheint nicht.
   - **Zählliste (PDF)** – Anlage mit Soll, Mengen je Bereich, Gesamt, Differenz und Status.
   - **Ergebnis (CSV)** – für Excel.
   Jeweils „Teilen“ (E-Mail, Drive, Druck-App) oder „Auf dem Gerät speichern“.
   Danach **Inventur abschließen**.
4. Die Startseite ist die **Gesamtübersicht** aller Kunden (offen / ohne Differenz / Differenz).
   ⋮ → „Gesamtübersicht teilen (CSV)“ für Excel.

Neu- und Refware werden über die Artikelnummer erkannt: endet sie auf `Ref`, ist es Refware.

## Bauen
GitHub Actions baut bei jedem Push auf `inventur-app` oder `main` (Ordner `inventur/`) automatisch:
- `.github/workflows/inventur.yml` – Tests, APK, Offline-Prüfung, Release `inventur-neueste-version`
- `.github/workflows/inventur-test.yml` – Durchlauf im Android-Emulator mit Screenshots (Artefakt „screenshots“)

## Aufbau
| Datei | Inhalt |
|---|---|
| `Model.kt` | Inventur, Artikel, Zählungen, Status, Zeilen fürs Anschreiben |
| `Store.kt` | Speicherung (JSON im privaten App-Speicher), Barcode-Zuordnungen, Einstellungen |
| `Import.kt` | Excel-/CSV-Import ohne externe Bibliothek |
| `Documents.kt` | PDF-Anschreiben, PDF-Zählliste, CSV-Export |
| `MainActivity.kt` | Übersicht aller Inventuren |
| `EditActivity.kt` | Inventur anlegen / Kopfdaten |
| `CountActivity.kt` | Zählen mit Scanner |
| `ResultActivity.kt` | Ergebnis, Abschluss, Dokumente |
| `SettingsActivity.kt` | Einstellungen |
| `DataWedge.kt` | Automatische Einrichtung des Zebra-Scanners (Profil „Inventur“) |
