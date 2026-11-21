package ar.liviano.browser

import android.content.Context
import android.net.Uri

object Prefs {
    private val p get() = App.instance.getSharedPreferences("liviano", Context.MODE_PRIVATE)

    var userAgent: String
        get() = p.getString("ua", UserAgents.DEFAULT) ?: UserAgents.DEFAULT
        set(v) = p.edit().putString("ua", v).apply()

    var customUserAgent: String
        get() = p.getString("ua_custom", "") ?: ""
        set(v) = p.edit().putString("ua_custom", v).apply()

    var engine: String
        get() = p.getString("engine", "google") ?: "google"
        set(v) = p.edit().putString("engine", v).apply()

    /** 8 accesos directos de la pantalla de inicio (vacíos = null). */
    var shortcuts: List<Shortcut?>
        get() {
            val raw = p.getString("shortcuts", null) ?: return Shortcut.DEFAULTS
            return runCatching {
                val arr = org.json.JSONArray(raw)
                List(Shortcut.COUNT) { i ->
                    arr.optJSONObject(i)?.let { Shortcut(it.optString("name"), it.optString("url")) }
                        ?.takeIf { it.url.isNotBlank() }
                }
            }.getOrDefault(Shortcut.DEFAULTS)
        }
        set(list) {
            val arr = org.json.JSONArray()
            for (i in 0 until Shortcut.COUNT) {
                val sc = list.getOrNull(i)
                arr.put(if (sc == null) org.json.JSONObject.NULL
                        else org.json.JSONObject().put("name", sc.name).put("url", sc.url))
            }
            p.edit().putString("shortcuts", arr.toString()).apply()
        }

    var desktopByDefault: Boolean
        get() = p.getBoolean("desktop_default", false)
        set(v) = p.edit().putBoolean("desktop_default", v).apply()
}

data class Shortcut(val name: String, val url: String) {
    companion object {
        const val COUNT = 8
        val DEFAULTS: List<Shortcut?> = listOf(
            Shortcut("YouTube", "https://www.youtube.com/"),
            Shortcut("Gmail", "https://mail.google.com/"),
            Shortcut("Mercado Libre", "https://www.mercadolibre.com.ar/"),
            Shortcut("Wikipedia", "https://es.wikipedia.org/"),
            null, null, null, null,
        )
    }
}

object SearchEngines {
    data class Engine(val key: String, val name: String, val home: String, val query: String)

    val all = listOf(
        Engine("google", "Google", "https://www.google.com/", "https://www.google.com/search?q=%s"),
        Engine("ddg", "DuckDuckGo", "https://duckduckgo.com/", "https://duckduckgo.com/?q=%s"),
        Engine("bing", "Bing", "https://www.bing.com/", "https://www.bing.com/search?q=%s"),
        Engine("brave", "Brave Search", "https://search.brave.com/", "https://search.brave.com/search?q=%s"),
        Engine("startpage", "Startpage", "https://www.startpage.com/", "https://www.startpage.com/do/search?q=%s"),
    )

    val current get() = all.find { it.key == Prefs.engine } ?: all[0]

    fun searchUrl(text: String) = current.query.replace("%s", Uri.encode(text))
}

object UserAgents {
    const val DEFAULT = "default"
    const val CUSTOM = "custom"

    /** [desktop] indica si el agente es de escritorio (activa el viewport de escritorio). */
    data class Agent(val key: String, val label: String, val value: String?, val desktop: Boolean)

    // Editá estas cadenas si algún sitio se queja de versión vieja.
    val all = listOf(
        Agent(DEFAULT, "Predeterminado (Firefox Android)", null, false),
        Agent("chrome_android", "Chrome · Android",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36", false),
        Agent("safari_iphone", "Safari · iPhone",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1", false),
        Agent("chrome_windows", "Chrome · Windows",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36", true),
        Agent("firefox_windows", "Firefox · Windows",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:145.0) Gecko/20100101 Firefox/145.0", true),
        Agent("safari_mac", "Safari · macOS",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15", true),
        Agent(CUSTOM, "Personalizado…", null, false),
    )

    val current: Agent get() = all.find { it.key == Prefs.userAgent } ?: all[0]

    /** Cadena a usar, o null para la de Gecko. */
    fun override(): String? =
        if (current.key == CUSTOM) Prefs.customUserAgent.trim().ifEmpty { null } else current.value

    fun isDesktop(): Boolean {
        if (current.key == CUSTOM) {
            val ua = Prefs.customUserAgent
            return ua.isNotBlank() && !ua.contains("Mobile", true) && !ua.contains("Android", true)
        }
        return current.desktop
    }
}
