plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.anicloud.sovereign.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.anicloud.sovereign.prototype"
        minSdk = 31
        targetSdk = 36
        versionCode = 12
        versionName = "0.8.1-fluorescent-forge"

        buildConfigField("String", "TENSOR_DISPATCH_VERSION", "\"2.1.6\"")
        buildConfigField(
            "String",
            "TENSOR_DISPATCH_ARCHIVE_SHA256",
            "\"98aabbdce8607f6dc6ab7cb92217326eef24a8c97b973b69e62bd0ce14b7495b\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The first native-intelligence build is a Pixel 10 Pro reference
            // artifact. Keeping only arm64 avoids shipping unused desktop ABIs.
            abiFilters += "arm64-v8a"
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    // LiteRT-LM 0.16.1 is compiled against coroutines 1.9.0. Keep both
    // artifacts strict: newer runtimes remove SendChannel.close$default,
    // which otherwise crashes the process when generation completes.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0") {
        version { strictly("1.9.0") }
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0") {
        version { strictly("1.9.0") }
    }
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
