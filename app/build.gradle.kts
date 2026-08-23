import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.hmartinez94.tvrelay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hmartinez94.tvrelay"
        // 24 covers essentially every Google TV / Android TV / Fire TV device in
        // the field. The wizard default of 35 would exclude almost all of them.
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"

        // Read from local.properties (gitignored, not versioned). Get a free key
        // at https://www.thetvdb.com/dashboard/account/apikeys
        buildConfigField(
            "String",
            "TVDB_API_KEY",
            "\"${localProperties.getProperty("TVDB_API_KEY", "")}\""
        )
        // Same pattern as TVDB_API_KEY above. This is the shared default used
        // when a user hasn't entered their own TMDB key in Settings (see
        // Preferences.getEffectiveTmdbApiKey) - get a free key at
        // https://www.themoviedb.org/settings/api
        buildConfigField(
            "String",
            "TMDB_API_KEY",
            "\"${localProperties.getProperty("TMDB_API_KEY", "")}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProperties.getProperty("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Only sign with the release config if local.properties has a
            // keystore configured; otherwise leave the build unsigned instead
            // of failing, so `assembleRelease` still works on fresh checkouts.
            if (localProperties.getProperty("RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.leanback)
    implementation(libs.okhttp)
    // Core artifact only (QR generation). The full zxing-android-embedded
    // library adds a camera-based scanner we don't need.
    implementation(libs.zxing.core)
}
