plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val hasReleaseSigning = listOf(
    "BINGWA_UPLOAD_STORE_FILE",
    "BINGWA_UPLOAD_STORE_PASSWORD",
    "BINGWA_UPLOAD_KEY_ALIAS",
    "BINGWA_UPLOAD_KEY_PASSWORD"
).all { !System.getenv(it).isNullOrBlank() }

android {
    namespace = "com.bingwa.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bingwa.mobile"
        minSdk = 21
        targetSdk = 34
        versionCode = 126
        versionName = "3.0.96"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(System.getenv("BINGWA_UPLOAD_STORE_FILE"))
                storePassword = System.getenv("BINGWA_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("BINGWA_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("BINGWA_UPLOAD_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
