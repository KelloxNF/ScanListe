# Scan-Portal – Listen scannen und Inventur in einer App für das Zebra TC22

Beim Öffnen zeigt die App zwei Kacheln:
- **Scannen** – Listen mit dem Scanner füllen (ohne Duplikate), als CSV exportieren; mit **Multidata-QR-Code** für QR-Codes mit mehreren Werten (z. B. Genexis: Seriennummer/PON-SN-Paare).
- **Inventur** – Jahresinventur je Kunde: Systembestand importieren, zählen, Anschreiben und Zählliste als PDF, Ergebnis als CSV.

Die App läuft komplett offline (keine Internet-Berechtigung, keine Cloud-Sicherung). Die ausführliche
Bedienungsanleitung für Anwender liegt als separates Dokument „Scan-Portal – Dokumentation“ vor.

## Installieren und aktualisieren
1. Auf GitHub unter **Releases → „Scan-Portal – neueste Version“** die Datei `Scan-Portal.apk` laden.
2. Auf dem TC22 antippen und installieren bzw. aktualisieren.

Jede Version ist mit demselben Schlüssel (`app/scanportal.keystore`) signiert. Neue Versionen lassen sich
über die alte installieren, alle Daten bleiben erhalten. **Nie vorher deinstallieren** – das löscht alle Daten.

## Scannen (Listen)
- Spalten frei wählbar; bei mehreren Spalten füllt jeder Scan die Zeile von links nach rechts.
- Doppelte Werte werden abgewiesen (Fehlerton, Sprung zur Zeile).
- Steht ein Wert schon in einer **anderen** Liste, wird er übernommen, aber orange gewarnt
  („steht auch in Liste ‚Palette 3‘ (Zeile 12, 14.10.)“).
- **Multidata-QR-Code** (beim Anlegen oder ⋮ in einer Liste mit genau 2 Spalten): Ein QR-Code mit mehreren
  Werten wird paarweise auf Zeilen verteilt (1.+2. Wert → Zeile 1 usw.). Alles oder nichts bei ungerader
  Anzahl, halbem Paar oder anderem Partner; einzelne Barcodes werden abgelehnt; bereits erfasste Paare
  übersprungen. „Rückgängig“ nimmt immer ganze Zeilen zurück.
- **Packliste (PDF)** (⋮ in der Liste): A4-Blatt zum Beilegen zur Palette mit Bezeichnung, Kunde, Datum,
  Anzahl, Bemerkung, allen Werten, Seitenzahlen und Unterschriftslinien „Gepackt von / Geprüft von“,
  dazu eine Seite mit QR-Codes zum Einlesen beim Empfänger.
- **Wareneingang:** den QR-Code einer Packliste in einer Liste scannen → die Geräte werden zur Soll-Liste.
- **Vorlagen** (⋮ → „Als Vorlage speichern …“, dann + in der Übersicht) und **Nächste Palette**
  (wird bei erreichter Zielmenge angeboten, Name zählt hoch: „Palette 4“ → „Palette 5“).
- **Liste abschließen** (⋮ oder lange drücken in der Übersicht): nimmt keine Scans mehr an.
- Übersicht ⋮: **Sammelexport** (mehrere Listen als eine CSV oder Sammel-Packliste) und **Tagesbericht** (PDF/CSV).
- CSV: Semikolon, UTF-8 mit BOM, öffnet direkt in deutschem Excel; Schutz vor Formel-Einschleusung.

## Inventur
1. **Neue Inventur**: Kopfdaten und Systembestand (Excel/CSV, Spalten Artikelnummer, Artikel, Bestand,
   Bestand in Technik – Reihenfolge egal; gleiche Artikelnummern werden addiert).
2. **Zählen**: Kacheln (weiß = offen, grün = stimmt, rot = Differenz, gelb = geschätzt), Fortschrittsbalken und
   Stand je Zählbereich; Suche, Filterleiste
   über ⋮. Kachel antippen → Artikel-Bildschirm: Bereich wählen, scannen (nur bei geöffnetem Artikel),
   Menge eingeben, feste Menge je Scan, Werkzeuge (Bestand stimmt, Mengen korrigieren, geschätzt,
   Bemerkung, umbenennen, löschen). Unbekannte Barcodes werden je Kunde zugeordnet.
3. **Ergebnis & Dokumente**: Anschreiben (PDF, ohne Technik-Bestand), Zählliste (PDF), Ergebnis (CSV);
   optional auch Artikel mit Bestand 0. Teilen oder auf dem Gerät speichern, dann abschließen.

## Scanner (DataWedge)
Die App legt beim Start das Profil **„ScanPortal“** an (Intent-Ausgabe `de.scanportal.app.SCAN`,
Tastatur-Ausgabe und DataWedge-Piepton aus).

## Bauen und Testen
GitHub Actions baut bei jedem Push auf `scan-portal`:
- `.github/workflows/scanportal.yml` – Unit-Tests, Release-APK, Offline-Prüfung, Release `scanportal-neueste-version`
- `.github/workflows/scanportal-test.yml` – Durchlauf im Android-Emulator (API 29 und 30) mit Screenshots
  (Zweige `scanportal-screenshots-api29/-api30`)

## Aufbau
| Ordner | Inhalt |
|---|---|
| `de/scanportal/app` | Startbildschirm, Scanner-Einrichtung, gemeinsame Bausteine (sicheres Speichern, Töne, CSV, Teilen), Datensicherung |
| `de/scanliste/app` | Scannen: Listen, Multidata-QR-Code, Warnung über andere Listen (`OtherLists`), Packliste mit QR-Codes (`PackList`, `PackCode`), Vorlagen (`Templates`), Sammelexport und Tagesbericht (`ListReports`, `OverviewTools`), CSV |
| `de/inventur/app` | Inventur: Import, Zähl-Logik (`CountSession`), Zähl-Ansicht (`OverviewScreen`, `ArticleScreen`, `ArticleSheets`), PDF-Dokumente, CSV |
