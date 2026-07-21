plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/* CI stamps every build with the workflow run number, so each main build is
   a higher versionCode — that's what the in-app update check compares. */
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

/* Human-facing version, computed by the android.yml workflow at build time
   as "<year>.<week>.<patch>" (see that file) and passed in through the
   environment; the same value goes into version.json. Local builds with no
   CI env fall back to "dev". The versionCode above is what the update check
   actually compares. */
val appVersionName = System.getenv("APP_VERSION_NAME") ?: "dev"

android {
    namespace = "dev.vtlinh.noveldownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.vtlinh.noveldownloader"
        minSdk = 26
        targetSdk = 34
        versionCode = ciRunNumber
        versionName = appVersionName
    }

    /* one committed key signs every build: Android only installs an update
       over an existing app when signatures match, and CI runners would
       otherwise generate a fresh random debug key per run */
    signingConfigs {
        create("shared") {
            storeFile = file("../signing.p12")
            storePassword = "noveldownloader"
            keyAlias = "novel"
            keyPassword = "noveldownloader"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.media:media:1.7.0")
}
