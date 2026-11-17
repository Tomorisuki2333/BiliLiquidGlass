plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bililiquidglass.xposed"
    compileSdk = 37
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.bililiquidglass.xposed"
        minSdk = 28
        targetSdk = 35
        versionCode = 115
        versionName = "1.0.15"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "bililiquidglass"
                keyAlias = "bililiquidglass"
                keyPassword = "bililiquidglass"
                enableV2Signing = true
                enableV3Signing = true
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
            val relSigning = signingConfigs.findByName("release")
            signingConfig = if (relSigning?.storeFile?.exists() == true) {
                relSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
