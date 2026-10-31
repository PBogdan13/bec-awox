plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ro.bec.awox"
    compileSdk = 34

    defaultConfig {
        applicationId = "ro.bec.awox"
        minSdk = 26
        targetSdk = 34
        // fiecare build de pe GitHub primește un număr mai mare, ca actualizarea să se instaleze peste cea veche
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("bec") {
            storeFile = file("bec.keystore")
            storePassword = "becawox123"
            keyAlias = "bec"
            keyPassword = "becawox123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("bec")
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
        checkReleaseBuilds = false
        abortOnError = false
    }
}
