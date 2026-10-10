# Scan-Portal – ScanListe und Inventur in einer App für das Zebra TC22

Beim Öffnen zeigt die App zwei Kacheln:
- **Scannen** – die bisherige ScanListe: Listen mit dem Scanner füllen, als CSV exportieren.
- **Inventur** – Jahresinventur je Kunde: Systembestand importieren, zählen, Anschreiben und Zählliste als PDF.

Mit dem Zurück-Pfeil oben links kommt man jeweils wieder zu den Kacheln. Die App läuft komplett offline.

## Installieren
1. Auf GitHub unter **Releases → „Scan-Portal – neueste Version“** die Datei `Scan-Portal.apk` laden.
2. Auf dem TC22 antippen und installieren.
3. Die Einzel-Apps „ScanListe“ und „Inventur“ laufen unabhängig weiter. Ihre gespeicherten Daten werden
   **nicht automatisch übernommen** – Listen vorher als CSV exportieren bzw. Inventuren abschließen,
   danach können die Einzel-Apps deinstalliert werden.

Updates: Das Scan-Portal wird immer mit demselben Schlüssel (`app/scanportal.keystore`) signiert.
Neue Versionen lassen sich daher einfach über die alte installieren, alle Daten bleiben erhalten.

## Scannen (ScanListe)
Mehrere Listen anlegen, mit dem eingebauten Scanner befüllen, keine Duplikate, CSV-Export
(Semikolon, UTF-8, öffnet direkt in deutschem Excel). Bedienung wie bisher in der ScanListe-App.

## Inventur
### Ablauf je Kunde
1. **Neue Inventur**: Kunde (bekannte Kunden werden vorgeschlagen), Stichtag, Zähler, Schreiber, Zählort.
   **Systembestand importieren**: Excel- (.xlsx) oder CSV-Export aus dem Lagersystem mit den Spalten
   *Artikelnummer, Artikel, Bestand, Bestand in Technik*. Die Reihenfolge der Spalten ist egal.
2. **Zählen**: oben den Bereich (Versand / Lager / Außenlager) wählen, Artikel scannen oder antippen, Menge eingeben.
   - „Jeder Scan = 1 Stück“ anhaken, wenn jedes Gerät einzeln gescannt wird.
   - Unbekannter Barcode (z. B. EAN statt Artikelnummer): einmal dem Artikel zuordnen – die App merkt es sich je Kunde.
     Haben Neu- und Refware dieselbe EAN, beide ankreuzen; die App fragt dann bei jedem Scan nach.
   - Langes Tippen auf einen Artikel (oder „Ändern“ im Mengen-Fenster): **Bezeichnung umbenennen**, Mengen je Bereich
     korrigieren, „Ist = Sollbestand“ anhaken (trägt den Soll im gewählten Bereich ein), „geschätzt“ markieren, Bemerkung. Die Artikelnummer bleibt; die neue Bezeichnung merkt sich die App
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

## Scanner (DataWedge)
Die App legt beim Start automatisch das DataWedge-Profil **„ScanPortal“** an (Intent-Ausgabe
`de.scanportal.app.SCAN`, Tastatur-Ausgabe aus). Es gilt für beide Bereiche der App.
Die alten Profile „ScanListe“ und „Inventur“ der Einzel-Apps stören nicht.

## Bauen
GitHub Actions baut bei jedem Push auf `scan-portal` oder `main` (Ordner `scanportal/`) automatisch:
- `.github/workflows/scanportal.yml` – Tests, APK, Offline-Prüfung, Release `scanportal-neueste-version`
- `.github/workflows/scanportal-test.yml` – Durchlauf im Android-Emulator mit Screenshots
  (Zweig `scanportal-screenshots`)

## Aufbau
| Ordner | Inhalt |
|---|---|
| `de/scanportal/app` | Startbildschirm mit Kacheln, gemeinsame Scanner-Einrichtung |
| `de/scanliste/app` | Scannen (Listen, CSV) |
| `de/inventur/app` | Inventur (Import, Zählen, PDF-Anschreiben, Zählliste, CSV) |
