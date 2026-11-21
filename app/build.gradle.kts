import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
}

android {
    namespace = "ar.liviano.browser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.assboxer.abrowser"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
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
        getByName("release") {
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

dependencies {
    implementation("org.mozilla.geckoview:geckoview:${property("geckoviewVersion")}")
}

// --- uBlock Origin integrado -------------------------------------------------
// Descarga la última versión estable de uBlock Origin (firmada por Mozilla) y la
// descomprime en assets/ como extensión incorporada. Borrá la carpeta para actualizarla.
val ublockDir = file("src/main/assets/extensions/ublock")
val ublockXpi = layout.buildDirectory.file("ublock/ublock.xpi").get().asFile
val ublockUrl = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"

val fetchUblock by tasks.registering {
    description = "Descarga uBlock Origin y lo integra en el APK"
    val dir = ublockDir
    val xpi = ublockXpi
    onlyIf { !File(dir, "manifest.json").exists() }
    doLast {
        xpi.parentFile.mkdirs()
        var uri = URI.create(ublockUrl)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = uri.toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "ABrowser-build")
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 10) {
                uri = uri.resolve(conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            check(code == 200) { "No se pudo descargar uBlock Origin (HTTP $code)" }
            break
        }
        conn.inputStream.use { input -> xpi.outputStream().use { input.copyTo(it) } }

        dir.deleteRecursively()
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        ZipInputStream(xpi.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.startsWith("META-INF/")) continue
                val out = File(dir, entry.name)
                check(out.canonicalPath.startsWith(root)) { "Entrada inválida en el xpi: ${entry.name}" }
                if (entry.isDirectory) out.mkdirs()
                else { out.parentFile.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
            }
        }
        println("uBlock Origin integrado en $dir")
    }
}

tasks.named("preBuild") { dependsOn(fetchUblock) }

// Nombre de los APK generados: ABrowser-arm64-v8a-release.apk, etc.
base { archivesName.set("ABrowser") }
