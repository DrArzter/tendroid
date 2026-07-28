plugins {
    id("com.android.application")
}

android {
    namespace = "com.motandrwall.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.motandrwall.app"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
        versionName = "0.1.0"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    signingConfigs {
        getByName("debug") {
            providers.environmentVariable("TENDROID_SIGNING_STORE_FILE").orNull?.let { signingFile ->
                storeFile = file(signingFile)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
