plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val hasReleaseSigning = listOf(
    "ADMINHUB_UPLOAD_STORE_FILE",
    "ADMINHUB_UPLOAD_STORE_PASSWORD",
    "ADMINHUB_UPLOAD_KEY_ALIAS",
    "ADMINHUB_UPLOAD_KEY_PASSWORD"
).all { !System.getenv(it).isNullOrBlank() }

android {
    namespace = "com.bingwa.adminhub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bingwa.adminhub"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(System.getenv("ADMINHUB_UPLOAD_STORE_FILE"))
                storePassword = System.getenv("ADMINHUB_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("ADMINHUB_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("ADMINHUB_UPLOAD_KEY_PASSWORD")
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
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "2.1.0"
    }
}

dependencies {
    constraints {
        implementation("org.bouncycastle:bcprov-jdk15on:1.76") {
            because("downgraded from 1.79 to resolve compatibility issues")
        }
        implementation("org.bouncycastle:bcprov-jdk18on:1.78.1") {
            because("avoids Gradle/CI classpath instrumentation failures on multi-release Java 21 classes")
        }
    }
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}
