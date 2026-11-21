import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ar.liviano.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "ar.liviano.browser"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Firma opcional con tu propia clave (variables de entorno o secretos de GitHub).
        // Sin ellas se usa la clave de depuración.
        val ks = System.getenv("LIVIANO_KEYSTORE")
        if (ks != null && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("LIVIANO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LIVIANO_KEY_ALIAS")
                keyPassword = System.getenv("LIVIANO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // Un APK por arquitectura: mucho más chico que uno universal.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        // Bibliotecas nativas comprimidas dentro del APK = descarga más liviana.
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:${property("geckoviewVersion")}")
}

// --- uBlock Origin integrado -------------------------------------------------
// Descarga la última versión estable de uBlock Origin (firmada por Mozilla) y la
// descomprime en assets/ como extensión incorporada. Borrá la carpeta para actualizarla.
val ublockDir = file("src/main/assets/extensions/ublock")
val ublockUrl = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"

val fetchUblock by tasks.registering {
    description = "Descarga uBlock Origin y lo integra en el APK"
    onlyIf { !File(ublockDir, "manifest.json").exists() }
    doLast {
        val xpi = layout.buildDirectory.file("ublock/ublock.xpi").get().asFile
        xpi.parentFile.mkdirs()
        var url = URL(ublockUrl)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Liviano-build")
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 10) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            check(code == 200) { "No se pudo descargar uBlock Origin (HTTP $code)" }
            break
        }
        conn.inputStream.use { input -> xpi.outputStream().use { input.copyTo(it) } }
        ublockDir.deleteRecursively()
        copy {
            from(zipTree(xpi))
            into(ublockDir)
            exclude("META-INF/**")
        }
        logger.lifecycle("uBlock Origin integrado en $ublockDir")
    }
}

tasks.named("preBuild") { dependsOn(fetchUblock) }
