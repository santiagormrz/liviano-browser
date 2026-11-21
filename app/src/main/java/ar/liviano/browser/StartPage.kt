package ar.liviano.browser

import android.net.Uri
import android.util.Base64

/**
 * Pantalla de inicio de las pestañas nuevas: logo, buscador y 8 accesos directos
 * (dos filas de 4). Se genera en el momento como una página data: con los colores
 * de ASSBOXER.
 *
 * Los accesos vacíos apuntan a [EDIT_PREFIX]; la app intercepta esa dirección y
 * abre el editor nativo (la página data: no puede guardar nada por sí misma).
 */
object StartPage {
    private const val PREFIX = "data:text/html;charset=utf-8;base64,"
    private const val MARKER = "<!--abrowser-start-->"
    const val EDIT_PREFIX = "https://abrowser.invalid/editar/"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun tile(index: Int, sc: Shortcut?): String {
        if (sc == null) {
            return """<a class="tile empty" href="$EDIT_PREFIX$index"><span class="ico">+</span><span class="lbl">Agregar</span></a>"""
        }
        val host = Uri.parse(sc.url).host ?: sc.url
        val name = sc.name.ifBlank { host.removePrefix("www.") }
        val initial = esc(name.take(1).uppercase())
        val favicon = "https://www.google.com/s2/favicons?sz=64&domain=" + Uri.encode(host)
        return """<a class="tile" href="${esc(sc.url)}"><span class="ico" data-l="$initial">""" +
            """<img src="${esc(favicon)}" alt="" onerror="this.remove()"></span>""" +
            """<span class="lbl">${esc(name)}</span></a>"""
    }

    fun url(): String {
        val engine = SearchEngines.current
        val action = engine.query.substringBefore('?')
        val tiles = Prefs.shortcuts.mapIndexed { i, sc -> tile(i, sc) }.joinToString("\n")
        val html = """
            <!DOCTYPE html>$MARKER
            <html lang="es"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="dark">
            <title>Pestaña nueva</title>
            <style>
              * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
              html, body { min-height: 100%; margin: 0; }
              body { color: #fff; font-family: system-ui, "Roboto", sans-serif;
                     background: linear-gradient(160deg, #000000 0%, #0e0b1c 35%, #1d0e23 60%, #490b22 85%, #600719 100%) fixed;
                     display: flex; flex-direction: column; min-height: 100vh; }
              main { flex: 1; display: flex; flex-direction: column; align-items: center;
                     justify-content: center; padding: 28px 18px; gap: 26px; }
              .logo { width: 96px; height: 58px; }
              form { width: 100%; max-width: 520px; }
              input { width: 100%; height: 50px; padding: 0 22px; font-size: 16px; color: #fff;
                      background: rgba(255,255,255,.08); border: 1px solid rgba(255,255,255,.16);
                      border-radius: 25px; outline: none; }
              input::placeholder { color: rgba(255,255,255,.55); }
              input:focus { border-color: rgba(255,255,255,.45); background: rgba(255,255,255,.12); }
              .grid { width: 100%; max-width: 520px; display: grid;
                      grid-template-columns: repeat(4, 1fr); gap: 18px 8px; }
              .tile { display: flex; flex-direction: column; align-items: center; gap: 7px;
                      text-decoration: none; color: #fff; min-width: 0; }
              .ico { width: 54px; height: 54px; border-radius: 16px; display: flex;
                     align-items: center; justify-content: center; position: relative;
                     background: rgba(255,255,255,.10); border: 1px solid rgba(255,255,255,.10); }
              .ico::before { content: attr(data-l); font-size: 22px; font-weight: 700; }
              .ico img { width: 30px; height: 30px; position: absolute; border-radius: 6px; }
              .empty .ico { background: transparent; border: 1.5px dashed rgba(255,255,255,.28);
                            font-size: 26px; font-weight: 300; color: rgba(255,255,255,.6); }
              .lbl { font-size: 12px; max-width: 100%; overflow: hidden; text-overflow: ellipsis;
                     white-space: nowrap; color: rgba(255,255,255,.85); }
              .empty .lbl { color: rgba(255,255,255,.45); }
              .tile:active .ico { transform: scale(.94); }
              footer { text-align: center; padding: 14px 16px 18px; font-size: 10.5px;
                       color: rgba(255,255,255,.38); letter-spacing: .4px; }
            </style>
            </head><body>
            <main>
              <svg class="logo" viewBox="0 0 70 42" aria-label="ABrowser">
                <g fill="none" stroke="#fff" stroke-width="8">
                  <path d="M5,41 L5,17 A12,12 0 0 1 29,17 L29,41"/>
                  <path d="M5,27 L29,27"/>
                  <path stroke-linejoin="miter" d="M45,37 L45,5 L56,5 A8,8 0 0 1 56,21 L45,21 L58,21 A8,8 0 0 1 58,37 Z"/>
                </g>
              </svg>
              <form action="$action" method="get">
                <input type="search" name="q" placeholder="Buscar con ${engine.name}"
                       autocomplete="off" enterkeyhint="search">
              </form>
              <div class="grid">
            $tiles
              </div>
            </main>
            <footer>Desarrollado por ASSBOXER</footer>
            </body></html>
        """.trimIndent()
        return PREFIX + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
    }

    fun isStart(url: String?): Boolean {
        if (url == null || !url.startsWith(PREFIX)) return false
        return runCatching {
            String(Base64.decode(url.substring(PREFIX.length), Base64.DEFAULT)).contains(MARKER)
        }.getOrDefault(false)
    }

    /** Índice del acceso a editar si [url] es un enlace "Agregar", o null. */
    fun editIndex(url: String): Int? =
        if (url.startsWith(EDIT_PREFIX)) url.removePrefix(EDIT_PREFIX).toIntOrNull()?.takeIf { it in 0 until Shortcut.COUNT }
        else null
}
