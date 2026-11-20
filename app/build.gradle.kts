import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.voiceagent.oneplus13"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voiceagent.oneplus13"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"\"")
        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"\"")
        buildConfigField("String", "MAX_SERVICE_TOKEN", "\"\"")
        buildConfigField("String", "MAX_COMMUNITY_ID", "\"${localProps.getProperty("MAX_COMMUNITY_ID", "")}\"")
    }

    buildTypes {
        create("recovery") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".balanced"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Нужны системному оверлею (overlay/OverlayLifecycleOwner.kt) — Compose внутри
    // окна WindowManager, добавленного из Service, требует свой Lifecycle/
    // ViewModelStore/SavedStateRegistry вместо тех, что бесплатно даёт Activity.
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // Нужен, чтобы MainScreen мог отличить телефон от планшета/ПК (адаптивный UI)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    // Icons.Filled.Settings / Icons.Filled.Send, используемые в новом UI
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.vosk.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    // Vosk's published POM resolves the desktop JNA jar by default; Android
    // requires the AAR so libjnidispatch.so is bundled for each device ABI.
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.security.crypto)
}

