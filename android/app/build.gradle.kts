import java.net.URI
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val releaseOriginUrl = providers.gradleProperty("INTERMIX_RELEASE_ORIGIN_URL")
    .orElse(providers.environmentVariable("INTERMIX_RELEASE_ORIGIN_URL"))
    .map { it.trim() }
val releaseManifestPublicKeyBase64 =
    providers.gradleProperty("INTERMIX_RELEASE_MANIFEST_PUBLIC_KEY_B64")
        .orElse(providers.environmentVariable("INTERMIX_RELEASE_MANIFEST_PUBLIC_KEY_B64"))
        .map { it.filterNot(Char::isWhitespace) }
val releaseApkCertificateSha256 =
    providers.gradleProperty("INTERMIX_RELEASE_APK_CERT_SHA256")
        .orElse(providers.environmentVariable("INTERMIX_RELEASE_APK_CERT_SHA256"))
        .map { it.trim() }

android {
    namespace = "dev.anicloud.sovereign.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.anicloud.sovereign.prototype"
        minSdk = 31
        targetSdk = 36
        versionCode = 23
        versionName = "0.8.12-release-origin"

        buildConfigField("String", "TENSOR_DISPATCH_VERSION", "\"2.2.0\"")
        buildConfigField(
            "String",
            "TENSOR_DISPATCH_ARCHIVE_SHA256",
            "\"b4c8380df3e9652677dbb93a5aad4499eb756a9b7d9651a9baacb122faadbf0d\"",
        )
        // Public trust anchors, supplied by the protected release pipeline. Debug builds may leave
        // them empty; the release pre-build gate below rejects missing or malformed values.
        buildConfigField(
            "String",
            "RELEASE_ORIGIN_URL",
            releaseOriginUrl.getOrElse("").asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "RELEASE_MANIFEST_ED25519_PUBLIC_KEY_B64",
            releaseManifestPublicKeyBase64.getOrElse("").asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "RELEASE_APK_CERT_SHA256",
            releaseApkCertificateSha256.getOrElse("").asBuildConfigString(),
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
            isDebuggable = false
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

val verifyReleaseTrustConfiguration by tasks.registering {
    group = "verification"
    description = "Rejects release APKs without canonical, locally pinned update trust anchors."
    inputs.property("releaseOriginUrl", releaseOriginUrl.orElse(""))
    inputs.property("releaseManifestPublicKeyBase64", releaseManifestPublicKeyBase64.orElse(""))
    inputs.property("releaseApkCertificateSha256", releaseApkCertificateSha256.orElse(""))

    doLast {
        val originValue = releaseOriginUrl.getOrElse("")
        val publicKeyValue = releaseManifestPublicKeyBase64.getOrElse("")
        val signerValue = releaseApkCertificateSha256.getOrElse("")
        if (originValue.isEmpty() || publicKeyValue.isEmpty() || signerValue.isEmpty()) {
            throw GradleException(
                "Release builds require INTERMIX_RELEASE_ORIGIN_URL, " +
                    "INTERMIX_RELEASE_MANIFEST_PUBLIC_KEY_B64, and " +
                    "INTERMIX_RELEASE_APK_CERT_SHA256.",
            )
        }

        val origin = runCatching { URI(originValue) }.getOrElse {
            throw GradleException("The Intermix release origin is not a valid URI.", it)
        }
        val host = origin.host?.lowercase().orEmpty()
        val numericIpv4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
        if (
            !origin.scheme.orEmpty().equals("https", ignoreCase = true) ||
            host.isEmpty() ||
            origin.userInfo != null ||
            (origin.port != -1 && origin.port != 443) ||
            '.' !in host ||
            host == "localhost" ||
            host.endsWith(".local") ||
            numericIpv4.matches(host) ||
            ':' in host ||
            '%' in origin.rawPath.orEmpty() ||
            '\\' in origin.rawPath.orEmpty() ||
            origin.query != null ||
            origin.fragment != null
        ) {
            throw GradleException(
                "The Intermix release origin must be a public HTTPS hostname on port 443 " +
                    "without credentials, redirects, encoded path separators, a query, or a fragment.",
            )
        }

        val publicKeyBytes = runCatching { Base64.getDecoder().decode(publicKeyValue) }.getOrElse {
            throw GradleException("The release-manifest public key is not canonical Base64.", it)
        }
        runCatching {
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        }.getOrElse {
            throw GradleException("The release-manifest public key is not an Ed25519 X.509 key.", it)
        }
        if (!Regex("^[0-9a-f]{64}$").matches(signerValue)) {
            throw GradleException("The release APK certificate pin must be one lowercase SHA-256 digest.")
        }
    }
}

// Debug builds deliberately remain available with the updater disabled. Every release build,
// including a local one, fails closed until all three public trust anchors pass validation.
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(verifyReleaseTrustConfiguration)
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
    implementation("androidx.work:work-runtime-ktx:2.11.2")
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
