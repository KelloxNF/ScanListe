# ScanListe – Scan-App für Zebra TC22

Mehrere Listen anlegen, mit dem eingebauten Scanner befüllen, lokal speichern, als CSV exportieren.

## Funktionen
- Beliebig viele Listen, jeweils mit frei benannten Spalten (z. B. `Seriennummer, EAN`)
- Scannen füllt die Tabelle zeilenweise nach unten. Bei mehreren Spalten wird eine Zeile
  von links nach rechts gefüllt, dann geht es in der nächsten Zeile weiter.
  Die nächste Zielzelle ist blau markiert.
- **Keine Duplikate:** Ein Wert darf in einer Liste nur einmal vorkommen (über alle Spalten).
  Bei einem doppelten Scan gibt es einen Fehlerton, eine Vibration und eine rote Meldung
  mit der Zeile, in der der Wert schon steht.
- Alle Barcode-Typen: EAN-8/13, UPC, Code 128/39, 2/5 Interleaved, QR, DataMatrix, PDF417
- Automatische Speicherung nach jedem Scan
- CSV-Export: „Als CSV speichern …“ (Speicherort wählen) oder „CSV teilen“ (E-Mail, Drive …)
  Format: Semikolon, UTF-8 mit BOM, öffnet sich direkt korrekt in deutschem Excel
- Letzten Scan rückgängig machen, einzelne Zeilen per langem Tippen löschen
- Manuelle Eingabe als Ersatz, falls ein Code nicht lesbar ist
- Listen umbenennen oder löschen per langem Tippen in der Übersicht

## Bauen und installieren
1. [Android Studio](https://developer.android.com/studio) installieren.
2. Den Ordner `ScanListe` öffnen (File → Open). Android Studio lädt Gradle und das SDK
   automatisch herunter, beim ersten Mal dauert das einige Minuten.
3. Auf dem TC22 den Entwicklermodus aktivieren: Einstellungen → Über das Telefon →
   7× auf „Build-Nummer“ tippen. Danach unter Entwickleroptionen „USB-Debugging“ einschalten.
4. TC22 per USB anschließen und in Android Studio auf ▶ (Run) klicken.

Alternativ: Build → Build App Bundle(s)/APK(s) → Build APK(s). Die Datei
`app/build/outputs/apk/debug/app-debug.apk` kann man auf das Gerät kopieren und installieren
oder per MDM (z. B. StageNow, SOTI) verteilen.

## Scanner (DataWedge)
Die App legt beim ersten Start automatisch ein DataWedge-Profil „ScanListe“ an. Es schickt
Scans per Intent (`de.scanliste.app.SCAN`) an die App und schaltet die Tastatur-Ausgabe ab.
Es ist keine manuelle Einrichtung nötig.

Falls die Scans nicht ankommen: DataWedge-App öffnen → Profil „ScanListe“ prüfen
(Profil aktiv, zugeordnete App `de.scanliste.app`, Intent-Ausgabe an, Aktion
`de.scanliste.app.SCAN`, Zustellung „Broadcast Intent“).

## Hinweis zu Excel
Lange Zahlen wie Seriennummern oder EAN-Codes zeigt Excel beim Doppelklick auf eine CSV-Datei
manchmal als `4,00638E+12` an. Die Datei selbst ist korrekt. Zum sauberen Öffnen in Excel
nutzt man Daten → Aus Text/CSV und stellt die Spalte auf „Text“.

## Aufbau
| Datei | Inhalt |
|---|---|
| `ScanList.kt` | Datenmodell, Zeilen-/Spaltenlogik, Duplikatprüfung |
| `ListStore.kt` | Speicherung als JSON im privaten App-Speicher |
| `Csv.kt` | CSV-Erzeugung |
| `DataWedge.kt` | Automatische Einrichtung des Zebra-Scanners |
| `MainActivity.kt` | Listenübersicht |
| `ListActivity.kt` | Tabelle und Scannen |
