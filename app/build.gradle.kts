plugins {
    id("com.android.application")
}

android {
    namespace = "dev.lean.pdf"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lean.pdf"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // A fixed key committed with the project so every build (yours or GitHub's) can
    // update the installed app in place. Fine for personal sideloading; before publishing
    // to a store, create a private key and keep it out of the repo.
    signingConfigs {
        create("lean") {
            storeFile = file("lean-pdf.keystore")
            storePassword = "leanpdf"
            keyAlias = "lean"
            keyPassword = "leanpdf"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("lean")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Intentionally no dependencies: no AndroidX, no Kotlin stdlib, no PDF library.
// Rendering uses android.graphics.pdf.PdfRenderer, built into the OS.
