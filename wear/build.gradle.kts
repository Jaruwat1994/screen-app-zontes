plugins {
    alias(libs.plugins.android.application)
}

// Wear OS companion for OpenCfMoto. It MUST keep the phone app's applicationId and signing key:
// the Wearable Data Layer only delivers messages between apps that share both.
android {
    namespace = "dev.zanderp.opencfmoto.wear"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "th.co.chaixi.zridescreen"
        minSdk = 30          // Wear OS 3 (Galaxy Watch 4 and newer)
        targetSdk = 36
        versionCode = 1
        versionName = ((project.findProperty("appVersion") as String?) ?: "1") +
            ((project.findProperty("versionSuffix") as String?) ?: "-dev")
    }

    // Same channels as :app — the watch app must carry the phone app's applicationId.
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            isDefault = true
        }
        create("play") {
            dimension = "store"
        }
    }

    // ZRide Screen release key: -PzrideKeystore=/path/zride-release.jks -PzrideKeystorePassword=…
    // (kept outside the repo, e.g. in $GRADLE_USER_HOME/gradle.properties; CI writes it from secrets).
    // Without it, release builds fall back to the debug key.
    val zrideKeystore = (project.findProperty("zrideKeystore") as String?)?.takeIf { file(it).exists() }
    signingConfigs {
        create("debugRelease") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (zrideKeystore != null) create("zride") {
            storeFile = file(zrideKeystore)
            storePassword = project.findProperty("zrideKeystorePassword") as String
            keyAlias = "zride"
            keyPassword = project.findProperty("zrideKeystorePassword") as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (zrideKeystore != null) "zride" else "debugRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.play.services.wearable)
    // Tile + watch-face complication (RideScreen AA v1.2).
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.complications)
    implementation(libs.androidx.concurrent.futures)
}
