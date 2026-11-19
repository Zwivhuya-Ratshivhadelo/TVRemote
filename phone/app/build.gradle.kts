plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.livingroom.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.livingroom.remote"
        minSdk = 29
        targetSdk = 34
        // GitHub numbers each build, so every new build is an update of the one before.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1." + (System.getenv("VERSION_CODE") ?: "0")
    }

    // Builds made on GitHub are signed with the Living Room key, so the apps can update themselves.
    signingConfigs {
        create("living") {
            storeFile = System.getenv("KEYSTORE")?.let { file(it) }
            storePassword = "livingroom"
            keyAlias = "remote"
            keyPassword = "livingroom"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (System.getenv("KEYSTORE") != null) "living" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

