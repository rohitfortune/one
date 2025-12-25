import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    id("org.jetbrains.kotlin.kapt")
    id("kotlin-parcelize")
}

android {
    namespace = "com.rohit.one"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rohit.one"
        minSdk = 26 
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // OAuth build config fields (set via local.properties or CI if needed).
        // Read from local.properties if present; otherwise default to empty strings.
        //
        // NOTE: These values are optional for the current GoogleSignIn + GoogleAuthUtil
        // on-device flow. The app can obtain Drive appData access using an Android OAuth
        // client registered with your package name + SHA-1 in Google Cloud Console.
        //
        // This local.properties reader is kept as a convenience so you can supply a
        // web client ID or AppAuth redirect URI (for PKCE or server flows) at build time
        // without editing source. Leaving these empty is safe and does not affect the
        // standard GoogleSignIn behavior.
        val localPropsFile = rootProject.file("local.properties")
        val localProps = Properties()
        if (localPropsFile.exists()) {
            localProps.load(localPropsFile.inputStream())
        }
        val oauthClientId: String = localProps.getProperty("OAUTH_CLIENT_ID", "")
        val oauthRedirectUri: String = localProps.getProperty("OAUTH_REDIRECT_URI", "")

        buildConfigField("String", "OAUTH_CLIENT_ID", "\"${oauthClientId}\"")
        buildConfigField("String", "OAUTH_REDIRECT_URI", "\"${oauthRedirectUri}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8 
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8" 
    }
    buildFeatures {
        compose = true
        // Enable BuildConfig generation so buildConfigField values are available at runtime
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.navigation.compose)
    // Compose Foundation (provides HorizontalPager via androidx.compose.foundation.pager)
    // use version catalog for foundation when possible; fall back to explicit coordinate
    // (the catalog may not have foundation declared; if it does, use libs.androidx.compose.foundation)
    // Keep explicit coordinate as a safe fallback
    implementation("androidx.compose.foundation:foundation")
    // Coil for image loading in Compose (thumbnails for attachments)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.compose.foundation)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.compose.material.icons.extended)

    // Jetpack Security for encrypted storage (MasterKey/Crypto) used by Passwords feature
    implementation(libs.androidx.security.crypto)

    // Biometric authentication for revealing passwords securely
    implementation(libs.androidx.biometric)

    // Google Sign-In (used to obtain OAuth tokens to talk to Drive appData)
    implementation(libs.play.services.auth)

    // Microsoft Authentication Library (MSAL) for OneDrive
    implementation("com.microsoft.identity.client:msal:5.1.0")

    // AndroidX Credential Manager (v1.5.0) via version catalog alias
    implementation(libs.androidxCredentialsV150)

    // HTTP client for Drive REST calls
    implementation(libs.okhttp)
    // JSON parsing
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    // Moshi codegen for @JsonClass(generateAdapter = true)
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.15.2")

    // Coroutines (Android) for MainScope().launch usage in MainActivity
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Add lifecycle runtime compose to use LocalLifecycleOwner from androidx.lifecycle.compose
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Removed AppAuth fallback - migration to Google Identity (GoogleSignIn/AuthorizationClient)
    // If you need PKCE fallback later, re-add AppAuth coordinates here.

    // DocumentFile API to browse SAF trees
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}