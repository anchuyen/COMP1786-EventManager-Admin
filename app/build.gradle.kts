plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)   // đọc google-services.json
}

android {
    namespace = "com.example.eventmanager"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.eventmanager.sqlite"
        minSdk = 24
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    implementation(platform(libs.firebase.bom))   // BoM chọn phiên bản Firebase tương thích
    implementation(libs.firebase.firestore)
    implementation(libs.zxing.core)              // QR code generation (feature f)
    implementation(libs.play.services.location)  // GPS location (feature f)
}