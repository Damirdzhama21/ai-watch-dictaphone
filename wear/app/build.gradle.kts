import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Настройки берутся из -P параметров, переменных окружения или local.properties
fun cfg(name: String, default: String = ""): String {
    val f = rootProject.file("local.properties")
    val local: String? = if (f.exists()) {
        val p = Properties()
        f.inputStream().use { p.load(it) }
        p.getProperty(name)
    } else null
    return (project.findProperty(name) as String?) ?: System.getenv(name) ?: local ?: default
}

android {
    namespace = "kz.damird.watchrec"
    compileSdk = 34

    defaultConfig {
        applicationId = "kz.damird.watchrec"
        minSdk = 30          // Wear OS 3+
        targetSdk = 34       // OnePlus Watch 3 = Wear OS 5 (API 34)
        versionCode = (cfg("VERSION_CODE", "2")).toInt()
        versionName = cfg("VERSION_NAME", "0.2.0")

        buildConfigField("String", "SERVER_URL", "\"${cfg("SERVER_URL", "https://rec.example.com/rec")}\"")
        buildConfigField("String", "UPLOAD_TOKEN", "\"${cfg("UPLOAD_TOKEN", "change-me")}\"")
        // true = открыл приложение (кнопкой/иконкой) -> запись стартует сразу
        buildConfigField("boolean", "AUTO_START", cfg("AUTO_START", "true"))
    }

    signingConfigs {
        create("release") {
            val ks = cfg("KEYSTORE_PATH")
            if (ks.isNotEmpty() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = cfg("KEYSTORE_PASS")
                keyAlias = cfg("KEY_ALIAS")
                keyPassword = cfg("KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("androidx.wear.tiles:tiles:1.4.0")
    implementation("androidx.wear.protolayout:protolayout:1.2.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
