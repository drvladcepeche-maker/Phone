plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStorePath = System.getenv("PHONE_KEYSTORE_PATH")
val signingStorePassword = System.getenv("PHONE_KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("PHONE_KEY_ALIAS")
val signingKeyPassword = System.getenv("PHONE_KEY_PASSWORD")
val stableSigningEnabled = listOf(signingStorePath, signingStorePassword, signingKeyAlias, signingKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.phoneanalyzer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phoneanalyzer.next"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (stableSigningEnabled) {
            create("stable") {
                storeFile = file(signingStorePath!!)
                storePassword = signingStorePassword!!
                keyAlias = signingKeyAlias!!
                keyPassword = signingKeyPassword!!
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (stableSigningEnabled) signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (stableSigningEnabled) signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
