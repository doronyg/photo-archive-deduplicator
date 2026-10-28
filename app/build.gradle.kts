plugins {
    alias(libs.plugins.android.application)
    // Kotlin is built into AGP 9 (android.builtInKotlin=true), so no kotlin-android plugin
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.doronyg.photoarchivededuplicator"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.doronyg.photoarchivededuplicator"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}
