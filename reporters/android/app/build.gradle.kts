plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


android {
    namespace = "com.devicetimeline.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devicetimeline.agent"
        minSdk = 28
        targetSdk = 36
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 46
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.46"
    }

    // Optional release signing: pass -PkeystorePath=… (plus password/alias) in CI.
    val keystorePath = project.findProperty("keystorePath") as String?
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = project.findProperty("keystorePassword") as String?
                keyAlias = project.findProperty("keyAlias") as String?
                keyPassword = project.findProperty("keyPassword") as String?
            }
        }
    }

    buildTypes {
        debug {
            // The locally signed package also serves as the on-device diagnostic build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
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

    packaging {
        resources {
            // Only Bouncy Castle's AES-CCM primitive is used. These PQC test matrices are unrelated.
            excludes += "org/bouncycastle/pqc/crypto/picnic/**"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    constraints { implementation("com.google.errorprone:error_prone_annotations:2.18.0") }
    implementation(project(":hibi-band"))
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Optional Health Connect sync extension
    implementation("androidx.health.connect:connect-client:1.1.0-rc01")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

}
