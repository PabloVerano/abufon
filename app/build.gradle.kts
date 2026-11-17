import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// All call configuration lives in abufon.properties at the project root (gitignored).
val abufon = Properties().apply {
    val f = rootProject.file("abufon.properties")
    if (!f.exists()) throw GradleException("Missing abufon.properties. Copy abufon.properties.example and fill it in.")
    f.inputStream().use { load(it) }
}
fun cfg(key: String): String =
    abufon.getProperty(key)?.trim() ?: throw GradleException("abufon.properties is missing $key")
fun cfgOr(key: String, default: String): String = abufon.getProperty(key)?.trim() ?: default
fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Granny's photo: granny_photo.jpg or .png at the project root (gitignored, like abufon.properties).
// Without one, the neutral placeholder in app/placeholder/ is used. The chosen file is copied to
// app/photo-res/ (gitignored, outside build/ so `clean` in the same run can't remove it).
val grannyPhoto = listOf("jpg", "jpeg", "png").map { rootProject.file("granny_photo.$it") }.firstOrNull { it.exists() }
    ?: file("placeholder/granny_photo.png")
val photoRes = file("photo-res")
file("photo-res/drawable").let { dir ->
    dir.deleteRecursively()
    dir.mkdirs()
    val ext = if (grannyPhoto.extension.lowercase() == "png") "png" else "jpg"
    grannyPhoto.copyTo(File(dir, "granny_photo.$ext"))
}
if (grannyPhoto.parentFile == file("placeholder")) logger.lifecycle("Abufon: no granny_photo.jpg/.png at the project root, using the placeholder photo")

android {
    namespace = "com.abufon.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.abufon.app"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "PORTAL_SIP", quoted(cfg("PORTAL_SIP")))
        buildConfigField("String", "PORTAL_PASSWORD", quoted(cfg("PORTAL_PASSWORD")))
        buildConfigField("String", "GRANNY_SIP", quoted(cfg("GRANNY_SIP")))
        buildConfigField("String", "GRANNY_NAME", quoted(cfg("GRANNY_NAME")))
        buildConfigField("boolean", "AUTO_ANSWER", cfg("AUTO_ANSWER").toBoolean().toString())
        buildConfigField("int", "AUTO_ANSWER_SECONDS", cfgOr("AUTO_ANSWER_SECONDS", "5").toInt().toString())
        buildConfigField("int", "CALL_TIMEOUT_SECONDS", cfgOr("CALL_TIMEOUT_SECONDS", "30").toInt().toString())
        buildConfigField("String", "MEDIA_ENCRYPTION", quoted(cfgOr("MEDIA_ENCRYPTION", "srtp")))
        buildConfigField("int", "RING_MIN_VOLUME_PERCENT", cfgOr("RING_MIN_VOLUME_PERCENT", "60").toInt().toString())
        buildConfigField("boolean", "KIOSK", cfgOr("KIOSK", "false").toBoolean().toString())
        buildConfigField("boolean", "RECORD_CALLS", cfgOr("RECORD_CALLS", "true").toBoolean().toString())
        buildConfigField("int", "RECORD_KEEP_DAYS", cfgOr("RECORD_KEEP_DAYS", "3").toInt().toString())
        // Grandma's name is the designer's @string/grandma_name: generated here so one setting
        // changes it everywhere (it is deliberately not in res/values/strings.xml).
        resValue("string", "grandma_name", cfg("GRANNY_NAME").replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\""))

        ndk {
            // Portal is ARM. Drop x86 to keep the APK small.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    sourceSets["main"].res.srcDir(photoRes)

    buildFeatures { buildConfig = true }

    signingConfigs {
        // Sideloaded device app: sign release with the local debug key so `installRelease` just works.
        getByName("debug")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("org.linphone:linphone-sdk-android:5.5.39")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
