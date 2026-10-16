# Offline-Prüfbericht Scan-Portal
Commit bf4596b, gebaut 2026-10-16 12:56 UTC

## 1. Berechtigungen der APK
```
package: de.scanportal.app
uses-permission: name='android.permission.VIBRATE'
permission: de.scanportal.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='de.scanportal.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```
Netzwerk/Konten/Funk: keine

## 2. Backup-/Netz-Einstellungen im Manifest
```
        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false
        A: http://schemas.android.com/apk/res/android:fullBackupContent(0x010104eb)=false
        A: http://schemas.android.com/apk/res/android:usesCleartextTraffic(0x010104ec)=false
        A: http://schemas.android.com/apk/res/android:dataExtractionRules(0x0101063e)=@0x7f130000
```

## 3. Regeln für Cloud-Backup und Geräteumzug (data_extraction_rules)
```
error: failed to find file.
```

## 4. App-Komponenten (inkl. aus Bibliotheken)
```
E: activity (line=33)
E: intent-filter (line=36)
E: action (line=37)
E: category (line=39)
E: activity (line=44)
E: activity (line=49)
E: activity (line=56)
E: activity (line=63)
E: activity (line=70)
E: activity (line=77)
E: activity (line=83)
E: provider (line=95)
E: meta-data (line=99)
E: provider (line=106)
E: meta-data (line=111)
E: receiver (line=116)
E: intent-filter (line=122)
```

## 5. Automatisch startende Bibliotheks-Initialisierer (meta-data)
```
A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.lifecycle.ProcessLifecycleInitializer" (Raw: "androidx.lifecycle.ProcessLifecycleInitializer")
A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.profileinstaller.ProfileInstallerInitializer" (Raw: "androidx.profileinstaller.ProfileInstallerInitializer")
A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.support.FILE_PROVIDER_PATHS" (Raw: "android.support.FILE_PROVIDER_PATHS")
```

## 6. Cloud-/Tracking-/Hintergrund-Bibliotheken im Code
keine

## 7. Netzwerk-, Konto-, Sync- und Backup-Klassen im Code
```
Landroid/accounts/AccountManager;
Landroid/webkit/WebView;
Ljava/net/DatagramSocket
Ljava/net/Socket
Ljava/net/SocketAddress
Ljava/net/SocketException
Ljava/net/SocketImpl
```

## 8. Größte Code-Pakete (Herkunft des Codes)
```
     28 Lde/inventur/app
     26 Lde/scanliste/app
     23 Landroidx/core/util
     19 Lde/scanportal/app
     13 Ljava/nio/file
      7 Lkotlin/io/path
      7 Ljava/util/stream
      7 Ljava/util/function
      7 Ljava/lang/reflect
      7 Lcom/google/zxing
      7 Landroidx/core/os
      6 Ljava/util/concurrent
      6 Ljava/lang/ref
      5 Lorg/w3c/dom
      5 Ljava/util/zip
      5 Landroidx/core/view
      5 Landroidx/core/text
      4 Lkotlin/coroutines/intrinsics
      4 Landroidx/core/app
      4 Landroid/icu/util
      4 Landroid/app/job
      3 Lkotlinx/coroutines/flow
      3 Lkotlin/jvm/internal
      3 Ljava/util/regex
      3 Landroidx/core/net
```
