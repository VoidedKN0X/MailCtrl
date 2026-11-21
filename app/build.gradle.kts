plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing. On GitHub Actions the build job has no signing key at all: it sets
// UNSIGNED_RELEASE=true and gets app-release-unsigned.apk, which a separate job signs with apksigner
// (see .github/workflows/build.yml). An unsigned APK cannot be installed, so nothing unsigned can ship.
// A keystore from environment variables still works (SIGNING_KEYSTORE_PATH, KEYSTORE_PASSWORD,
// KEY_ALIAS, KEY_PASSWORD). Local builds without one fall back to the debug key; on CI a build that
// has neither a key nor UNSIGNED_RELEASE=true fails, so a debug-signed APK is never published as a release.
val releaseKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() && file(it).exists() }
val isCi: Boolean = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
val releaseSigningComplete: Boolean = releaseKeystorePath != null &&
    listOf("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all { !System.getenv(it).isNullOrBlank() }
val unsignedRelease: Boolean = System.getenv("UNSIGNED_RELEASE") == "true"

android {
    namespace = "com.vknxworks.mailctrl"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vknxworks.mailctrl"
        minSdk = 35
        targetSdk = 36
        // Bump both to publish a new release (see CHANGELOG.md).
        versionCode = 17
        versionName = "0.4.0"
        // 64-bit ARM phones only (arm64-v8a), like every Ctrl app.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                // Asked for explicitly: no signing here at all, the output is app-release-unsigned.apk.
                unsignedRelease -> null
                releaseSigningComplete -> signingConfigs.getByName("release")
                isCi -> throw GradleException(
                    "Release signing is not configured on CI (keystore, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) " +
                        "and UNSIGNED_RELEASE=true is not set. Refusing to sign the release with the debug key.",
                )
                else -> signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // No dependency metadata blob in the APK: it's unreadable to anyone checking the build.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/LICENSE.md", "/META-INF/NOTICE.md", "/META-INF/LICENSE.txt", "/META-INF/NOTICE.txt")
        // Bouncy Castle's OSGi manifests (desktop Java only). Its licence is in assets/licenses instead.
        resources.excludes += listOf("/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    // Java 17 bytecode from any JDK 17+ (like the other Ctrl apps), so unit tests also run where only a newer
    // JDK is installed; no toolchain lookup or download.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.ui.text.ExperimentalTextApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    // IMAP and SMTP: JavaMail's Android build (javax.mail), no Google libraries.
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    // Background checks for new mail, and wake-ups from ntfy.
    implementation(libs.androidx.work.runtime)
    // OpenPGP (encrypting, signing, keys): Bouncy Castle's lightweight API, MIT-style licence, from Maven Central.
    implementation(libs.bouncycastle.pg)
    implementation(libs.bouncycastle.prov)

    testImplementation(libs.junit)
}
