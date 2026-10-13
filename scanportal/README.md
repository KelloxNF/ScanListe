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
2. **Zählen**: Die Artikel erscheinen als Kacheln (nur Bezeichnung).
   Farbe: grau = noch nicht gezählt, grün = Bestand stimmt, rot = Differenz, gelb = geschätzt
   (bei Blindzählung blau = gezählt). Oben sucht man nach Name oder Artikelnummer;
   ⋮ → „Filter: nur nicht gezählte“ / „Filter: nur Differenzen“ zum An- und Ausschalten.
   - **Kachel antippen** öffnet das Artikel-Fenster: Artikelnummer, Soll, gezählte Menge je Bereich,
     Bereich wählen (Versand / Lager / Außenlager), „Feste Menge je Scan“, Menge von Hand, Rückgängig,
     „Korrigieren …“ (Bezeichnung, Mengen je Bereich, „Bestand stimmt“, geschätzt, Bemerkung, Löschen).
   - **Gescannt wird nur bei geöffnetem Artikel.** Ohne geöffneten Artikel zählt ein Scan nichts, es
     erscheint nur ein Hinweis. Ein Scan eines anderen Artikels im offenen Fenster wird abgewiesen.
   - Unbekannter Barcode im offenen Fenster: die App fragt, ob er diesem Artikel zugeordnet werden soll,
     und merkt es sich je Kunde (gleiche EAN bei Neu- und Refware: bei beiden zuordnen).
   - „Feste Menge je Scan“ (z. B. 20 je voller Verpackungseinheit) gilt für die ganze Zähl-Sitzung.
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
