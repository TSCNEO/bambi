plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
}

import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// APK para publicar en GitHub: si existe .public_build con "1", se compila SIN ninguna clave ni dato privado
// (ni las claves iniciales, ni el secreto de Trakt, ni la configuración de Ithil), pase lo que pase en local.properties.
val publicBuild: Boolean = rootProject.file(".public_build").let { it.exists() && it.readText().trim() == "1" }
val seedKeys: Boolean = !publicBuild && localProps.getProperty("seedKeys")?.trim()?.lowercase() == "true"
fun seed(name: String): String = if (seedKeys) localProps.getProperty(name) ?: "" else ""
fun privateProp(name: String): String = if (publicBuild) "" else localProps.getProperty(name)?.trim() ?: ""
val rdToken: String = seed("rdToken")
val subdlApiKey: String = seed("subdlApiKey")
val tmdbApiKey: String = seed("tmdbApiKey")
val traktClientId: String = localProps.getProperty("traktClientId")?.trim() ?: ""
val traktClientSecret: String = privateProp("traktClientSecret")
val traktRedirectUri: String = localProps.getProperty("traktRedirectUri")?.trim() ?: "urn:ietf:wg:oauth:2.0:oob"
val ithilMetadataUrl: String = privateProp("ithilMetadataUrl")
val ithilIndexKey: String = privateProp("ithilIndexKey")
val ithilArchivePrefix: String = privateProp("ithilArchivePrefix")

// Versión base de grodri99/bambi (el repositorio del que salen las mejoras): se enseña en Ajustes como
// "Versión 1.1.2 · base 1.0.6". Orden: variable de entorno (CI) → etiqueta de upstream/main → fichero versionado.
fun getUpstreamVersion(): String {
    System.getenv("UPSTREAM_VERSION")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.removePrefix("v") }
    runCatching {
        val proc = ProcessBuilder("git", "describe", "--tags", "--abbrev=0", "upstream/main")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out.removePrefix("v").takeIf { proc.exitValue() == 0 && it.isNotEmpty() && it != "upstream/main" }
    }.getOrNull()?.let { return it }
    rootProject.file("upstream_version.txt").takeIf { it.exists() }
        ?.readText()?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    return "1.0.7"
}

android {
    namespace = "com.shieldstream.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shieldstream.tv"
        minSdk = 21
        targetSdk = 35
        // Al publicar una versión nueva: subir versionCode (+1) y versionName (1.0.1, 1.1.0…).
        versionCode = 124
        versionName = "1.1.14"
        manifestPlaceholders["appLabel"] = "@string/app_name"

        buildConfigField("String", "UPSTREAM_VERSION", "\"${getUpstreamVersion()}\"")

        buildConfigField("String", "RD_TOKEN", "\"$rdToken\"")
        buildConfigField("String", "SUBDL_API_KEY", "\"$subdlApiKey\"")
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"$traktClientId\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"$traktClientSecret\"")
        buildConfigField("String", "TRAKT_REDIRECT_URI", "\"$traktRedirectUri\"")
        buildConfigField("String", "ITHIL_METADATA_URL", "\"$ithilMetadataUrl\"")
        buildConfigField("String", "ITHIL_INDEX_KEY", "\"$ithilIndexKey\"")
        buildConfigField("String", "ITHIL_ARCHIVE_PREFIX", "\"$ithilArchivePrefix\"")
    }

    flavorDimensions.add("version")
    productFlavors {
        create("stable") {
            dimension = "version"
            applicationId = "com.shieldstream.tv"
            manifestPlaceholders["appLabel"] = "Bambi"
            buildConfigField("String", "UPDATE_APK_NAME", "\"Bambi.apk\"")
        }
        create("beta") {
            dimension = "version"
            applicationId = "com.shieldstream.tv.beta"
            versionNameSuffix = ""
            manifestPlaceholders["appLabel"] = "Bambi Beta"
            buildConfigField("String", "UPDATE_APK_NAME", "\"Bambi-Beta.apk\"")
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
    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            storeFile = if (ksPath != null) file(ksPath) else rootProject.file(localProps.getProperty("releaseStoreFile") ?: "keystore/butaca.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: localProps.getProperty("releaseStorePassword") ?: "android"
            keyAlias = System.getenv("KEY_ALIAS") ?: localProps.getProperty("releaseKeyAlias") ?: "androiddebugkey"
            keyPassword = System.getenv("KEY_PASSWORD") ?: localProps.getProperty("releaseKeyPassword") ?: "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-pruebas"
            manifestPlaceholders["appLabel"] = "Bambi (pruebas)"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        // Release-like performance testing without touching the stable app or enabling self-updates.
        // Shares the debug package/data, but is non-debuggable so AGP packages the baseline profile.
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-benchmark"
            manifestPlaceholders["appLabel"] = "Bambi (benchmark)"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:strings"))
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":core:ui"))
    implementation(project(":feature:home"))
    implementation(project(":feature:details"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.tvprovider)
    implementation(libs.coil)
    implementation(files("libs/media3-decoder-av1-1.6.0.aar"))
    // Decoder de audio por software (FFmpeg: DTS, DTS-HD, TrueHD, AC3/E-AC3…). Sin él, en TVs sin licencia
    // DTS/TrueHD (Chromecast con Google TV 4K) las pistas se marcan como no soportadas y la película queda muda.
    // Lo carga core:media por reflexión (FfmpegAudioRenderer), así que este módulo no depende de él al compilar.
    implementation(files("libs/media3-decoder-ffmpeg-1.6.0.aar"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
