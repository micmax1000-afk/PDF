plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.micmax.pdf"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.micmax.pdf"
        minSdk = 26
        targetSdk = 34
        // Su GitHub il numero di build cresce a ogni compilazione (1000 + numero dell'esecuzione)
        versionCode = System.getenv("PDF_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.2.0"
        resourceConfigurations += listOf("it", "en")
    }

    // Firma fissa: la chiave arriva dai secret di GitHub (PDF_KEYSTORE_BASE64, PDF_KEYSTORE_PASSWORD).
    // Con la stessa firma Android installa gli aggiornamenti sopra la versione precedente.
    val ksFile = System.getenv("PDF_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    val ksPass = System.getenv("PDF_KEYSTORE_PASSWORD")
    signingConfigs {
        if (ksFile != null && !ksPass.isNullOrEmpty()) create("fixed") {
            storeFile = ksFile
            storePassword = ksPass
            keyAlias = "weblauncher"
            keyPassword = ksPass
        }
    }
    val fixedSigning = signingConfigs.findByName("fixed")

    buildTypes {
        release {
            if (fixedSigning != null) signingConfig = fixedSigning
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (fixedSigning != null) signingConfig = fixedSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.biometric:biometric:1.1.0")                 // blocco con impronta o PIN del telefono
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")   // firma, compila, evidenzia, pagine, comprimi, password
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
