plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bingemote"
    compileSdk = 35
    testBuildType = if (providers.gradleProperty("recoveryReleaseTest").isPresent) "release" else "debug"

    defaultConfig {
        applicationId = "com.bingemote"
        minSdk = 31
        targetSdk = 34
        versionCode = 46
        versionName = "1.17.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing key is private and never committed. CI passes it in through environment
    // variables from encrypted repository secrets. Without them the release build is unsigned.
    val ksFile: String? = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "bingemote"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The minified androidTest APK does NOT inherit the rules above; its
            // instrumentation runtime keeps live in test-proguard.pro.
            testProguardFiles("test-proguard.pro")
            if (ksFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Tink references these class-retained build-time annotations; not runtime code.
    compileOnly("com.google.errorprone:error_prone_annotations:2.53.0")
    // The minified release androidTest APK has the same Tink/test-only annotation
    // references on its own classpath; keep them compile-only there too.
    androidTestCompileOnly("com.google.errorprone:error_prone_annotations:2.53.0")
    // androidx.test.runner needs androidx.tracing at runtime. Debug gets it through
    // consistent resolution with the app classpath; the release androidTest APK does not
    // (NoClassDefFoundError: androidx.tracing.Trace in AndroidJUnitRunner.onCreate, run
    // 45344979448 logcat). Pinned to 1.0.0: the app classpath carries a strict 1.0.0
    // constraint (run 45347694554 conflict), and debug already runs the runner on 1.0.0.
    androidTestImplementation("androidx.tracing:tracing:1.0.0")
    // Runtime carrier for the minified release instrumentation process:
    // AndroidJUnitRunner.onCreate needs androidx.tracing.Trace, and R8 strips
    // it from the test APK (test minification does not apply this module's
    // keep rules). Carrying it in the app APK (kept via proguard-rules.pro)
    // resolves it for the shared instrumented classloader. A few KB; also
    // satisfies the existing strict 1.0.0 constraint natively.
    implementation("androidx.tracing:tracing:1.0.0")
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1") // the live mouse WebSocket
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.security:security-crypto:1.1.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("junit:junit:4.13.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    // Screenshot-rig only: drives the continuous hold-then-slide seek gesture as one touch.
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.1")
}
