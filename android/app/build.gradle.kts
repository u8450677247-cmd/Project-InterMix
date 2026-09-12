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
        versionCode = 22
        versionName = "0.8.11-scoped-autonomy"

        buildConfigField("String", "TENSOR_DISPATCH_VERSION", "\"2.2.0\"")
        buildConfigField(
            "String",
            "TENSOR_DISPATCH_ARCHIVE_SHA256",
            "\"b4c8380df3e9652677dbb93a5aad4499eb756a9b7d9651a9baacb122faadbf0d\"",
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
        // LiteRT's Tensor NPU backend receives applicationInfo.nativeLibraryDir.
        // Keep the pinned dispatcher as a real extracted file at that path rather
        // than loading it directly from the APK archive.
        jniLibs.useLegacyPackaging = true
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
    // LiteRT-LM 0.17.0 declares coroutines 1.11.0. Keep Android and core on
    // the same strict version so its precompiled runtime sees one ABI.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0") {
        version { strictly("1.11.0") }
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0") {
        version { strictly("1.11.0") }
    }
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json implementation is available on-device, while local JVM tests otherwise
    // receive only the throwing Android SDK stubs. Keep protocol parsing executable in CI.
    testImplementation("org.json:json:20260719")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
