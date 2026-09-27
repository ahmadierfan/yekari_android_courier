import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ── تنها جایی که دو اپ (مشتری/پیک) با هم فرق دارند ────────────────
val appKind = "courier"
val appId = "ir.yekari.courier"
val deepLinkScheme = "yekari-courier"
// ──────────────────────────────────────────────────────────────

fun prop(name: String): String =
    (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("ویژگی $name در gradle.properties تعریف نشده")

fun str(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// امضای انتشار: از keystore.properties (محلی، در گیت نیست) یا متغیرهای محیطی CI
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "ir.yekari.shell"
    compileSdk = 35

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 35
        versionCode = prop("yekari.versionCode").toInt()
        versionName = prop("yekari.versionName")

        buildConfigField("String", "APP_KIND", str(appKind))
        buildConfigField("String", "DEEP_LINK_SCHEME", str(deepLinkScheme))
        manifestPlaceholders["deepLinkScheme"] = deepLinkScheme
    }

    flavorDimensions += "env"
    productFlavors {
        listOf("dev", "prod").forEach { env ->
            create(env) {
                dimension = "env"
                val webUrl = prop("yekari.$env.webUrl").trimEnd('/')
                buildConfigField("String", "WEB_URL", str(webUrl))
                buildConfigField("String", "TRUSTED_HOSTS", str(prop("yekari.$env.trustedHosts")))
                manifestPlaceholders["appHost"] = URI(webUrl).host
                if (env == "dev") {
                    applicationIdSuffix = ".dev"
                    versionNameSuffix = "-dev"
                }
            }
        }
    }

    signingConfigs {
        val storeFile = secret("storeFile", "YEKARI_KEYSTORE")
        if (storeFile != null) {
            create("release") {
                this.storeFile = rootProject.file(storeFile)
                storePassword = secret("storePassword", "YEKARI_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "YEKARI_KEY_ALIAS")
                keyPassword = secret("keyPassword", "YEKARI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.exifinterface)
}
