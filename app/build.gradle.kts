import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Kunci penandatanganan dibaca dari keystore.properties supaya kata sandinya
// tidak ikut masuk ke berkas build. Kalau berkasnya tidak ada, varian rilis
// tetap bisa dirakit, hanya saja tidak tertandatangani.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.escpr.usbprint"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.escpr.usbprint"
        minSdk = 24
        targetSdk = 35
        // Dinaikkan tiap kali APK diserahkan. versionCode harus naik agar
        // Android mau memasang sebagai pembaruan; versionName yang dibaca orang.
        versionCode = 21
        versionName = "2.10"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
            // Semua skema tanda tangan dinyalakan. Bawaannya hanya v2, dan
            // sebagian pemasang bawaan HP -- terutama di ROM pabrikan -- masih
            // mencari blok v1 (JAR) sebelum melanjutkan.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // R8 dinyalakan setelah aplikasi terbukti jalan di HP. Yang dicari
            // lewat refleksi -- terutama konstruktor ViewModel -- dijaga oleh
            // proguard-rules.pro, dan keberadaannya di dalam DEX hasil rilis
            // diperiksa oleh tools/check_release_dex.py, bukan diandaikan.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Supaya nomor versi bisa ditampilkan di dalam aplikasi.
        buildConfig = true
    }

    // Nama berkas APK memuat versinya, sehingga dua build berbeda tidak pernah
    // bernama sama dan tidak saling menimpa di folder unduhan.
    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName =
                "Printigo-v${variant.versionName}-${variant.buildType.name}.apk"
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        // Robolectric merender tampilan Compose di JVM, jadi butuh resource asli.
        unitTests.isIncludeAndroidResources = true
    }
}

// Uji tampilan butuh androidx.compose.ui:ui-test-manifest, yang sengaja hanya
// dipasang di varian debug supaya tidak ikut terbawa ke rilis. Karena itu unit
// test dijalankan pada varian debug saja.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        // Penggantinya, HasHostTestsBuilder, masih berstatus incubating di AGP
        // 8.6, jadi tetap memakai properti lama sampai stabil.
        @Suppress("DEPRECATION")
        variant.enableUnitTest = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
