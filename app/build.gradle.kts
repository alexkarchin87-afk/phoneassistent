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

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"\"")
        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"\"")
        buildConfigField("String", "MAX_SERVICE_TOKEN", "\"\"")
        buildConfigField("String", "MAX_COMMUNITY_ID", "\"${localProps.getProperty("MAX_COMMUNITY_ID", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
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
    testImplementation("junit:junit:4.13.2")
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

    implementation(libs.vosk.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.security.crypto)
    implementation(libs.play.services.auth)
}

