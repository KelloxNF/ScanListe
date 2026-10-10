plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.scanportal.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.scanportal.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Fester Schlüssel im Repository: Jede neue Version lässt sich über die alte installieren,
    // ohne vorher zu deinstallieren – die gespeicherten Listen und Inventuren bleiben erhalten.
    signingConfigs {
        getByName("debug") {
            storeFile = file("scanportal.keystore")
            storePassword = "scanportal"
            keyAlias = "scanportal"
            keyPassword = "scanportal"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
