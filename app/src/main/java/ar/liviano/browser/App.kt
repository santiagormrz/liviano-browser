package ar.liviano.browser

import android.app.Application
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

class App : Application() {

    companion object {
        lateinit var instance: App
            private set

        const val UBO_ID = "uBlock0@raymondhill.net"
        const val UBO_PATH = "resource://android/assets/extensions/ublock/"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /**
     * Un único motor Gecko para toda la app. Es perezoso a propósito: GeckoView
     * levanta procesos hijos que también crean la Application, y ahí no queremos
     * iniciar otro runtime.
     */
    val runtime: GeckoRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(true)
            .consoleOutput(false)
            .build()
        GeckoRuntime.create(this, settings).also { installUblock(it) }
    }

    var ublock: WebExtension? = null
        private set

    private val waiting = mutableListOf<(WebExtension) -> Unit>()

    /** Ejecuta [block] cuando uBlock Origin esté listo (o en el acto, si ya lo está). */
    fun whenUblockReady(block: (WebExtension) -> Unit) {
        val ext = ublock
        if (ext != null) block(ext) else waiting.add(block)
    }

    private fun installUblock(rt: GeckoRuntime) {
        rt.webExtensionController.ensureBuiltIn(UBO_PATH, UBO_ID).accept(
            { ext ->
                if (ext != null) {
                    ublock = ext
                    waiting.toList().forEach { it(ext) }
                    waiting.clear()
                }
            },
            { e -> Log.e("Liviano", "No se pudo cargar uBlock Origin", e) }
        )
    }
}
