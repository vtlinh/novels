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

    /* One directory per supported site, holding everything that is that
       site's business: its adapter, its own test class, and the real pages it
       is judged against.

           src/sites/<site>/main/<Site>.kt
           src/sites/<site>/test/java/<Site>Test.kt
           src/sites/<site>/test/resources/pages/<site>/…

       Site knowledge was previously spread across the shared source and test
       trees with the fixtures in a third place and one manifest every site
       had to be edited into, so adding or removing a site touched files that
       belonged to the others. Discovered from the filesystem rather than
       listed here, so a new site is a new directory and nothing else. */
    val siteRoots = file("src/sites").listFiles().orEmpty()
        .filter { it.isDirectory }.sortedBy { it.name }

    /* These hold Kotlin, and go on the JAVA source set on purpose: the Kotlin
       plugin's KotlinAndroidJavaSourceDirConfigurator feeds each Android
       source set's java trees into the Kotlin one through a lazy provider, so
       adding here reaches both compilers. Adding to a `kotlin` set instead
       would leave Java-side tooling blind to them. */
    sourceSets {
        getByName("main") {
            java.srcDirs(siteRoots.map { File(it, "main") })
        }
        getByName("test") {
            java.srcDirs(siteRoots.map { File(it, "test/java") })
            resources.srcDirs(siteRoots.map { File(it, "test/resources") })
        }
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
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")

    /* Plain JVM unit tests over the pure decision logic — no emulator, no
       Robolectric. See Renumber.kt for why that logic was pulled out of the
       engine: reasoning about it in place kept getting it wrong in ways that
       deleted or misfiled chapters. */
    testImplementation("junit:junit:4.13.2")

    /* A real SQLite for the migration tests. The statements live in Schema,
       which has no Android in it, so the same list the app executes on a
       device can be executed here against a real engine — see SchemaTest.
       Migrations run once per install on the only copy of a library the user
       has, and a swallowed exception in one of them already shipped. */
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
}
