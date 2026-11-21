package ar.liviano.browser

import android.util.Base64

/**
 * Pantalla de inicio de las pestañas nuevas. Se genera en el momento como una
 * página data: (sin red, sin archivos), con el buscador elegido en Ajustes.
 */
object StartPage {
    private const val PREFIX = "data:text/html;charset=utf-8;base64,"
    private const val MARKER = "<!--abrowser-start-->"

    fun url(): String {
        val engine = SearchEngines.current
        val action = engine.query.substringBefore('?')
        val html = """
            <!DOCTYPE html>$MARKER
            <html lang="es"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="light dark">
            <title>Pestaña nueva</title>
            <style>
              :root { --bg:#ffffff; --ink:#1f1f23; --soft:#8a8a93; --field:#f4f4f6; --line:#dadce0; --accent:#b4141b; }
              @media (prefers-color-scheme: dark) {
                :root { --bg:#1c1b20; --ink:#ececf1; --soft:#8f8f99; --field:#2a2930; --line:#3a3941; --accent:#ff5a5f; }
              }
              * { box-sizing: border-box; }
              html, body { height: 100%; margin: 0; }
              body { background: var(--bg); color: var(--ink);
                     font-family: system-ui, -apple-system, "Roboto", sans-serif;
                     display: flex; flex-direction: column; }
              main { flex: 1; display: flex; flex-direction: column; align-items: center;
                     justify-content: center; padding: 24px; gap: 22px; margin-top: -8vh; }
              .brand { display: flex; flex-direction: column; align-items: center; gap: 10px; }
              .logo { width: 84px; height: 84px; }
              h1 { margin: 0; font-size: 26px; font-weight: 600; letter-spacing: -0.3px; }
              form { width: 100%; max-width: 520px; }
              input { width: 100%; height: 48px; padding: 0 20px; font-size: 16px;
                      color: var(--ink); background: var(--field); border: 1px solid var(--line);
                      border-radius: 24px; outline: none; }
              input:focus { border-color: var(--accent); }
              footer { text-align: center; padding: 16px 16px 20px; font-size: 11px;
                       color: var(--soft); letter-spacing: 0.3px; }
            </style>
            </head><body>
            <main>
              <div class="brand">
                <svg class="logo" viewBox="20 30 68 48" aria-hidden="true">
                  <g fill="none" stroke="var(--accent)" stroke-width="6.5" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M26,72 L41,36 L60,72"/>
                    <path d="M60,72 L60,36 L67,36 A8,8 0 0 1 67,52 L60,52 L70,52 A10,10 0 0 1 70,72 Z"/>
                  </g>
                  <circle cx="41.5" cy="58" r="3.4" fill="var(--accent)"/>
                </svg>
                <h1>ABrowser</h1>
              </div>
              <form action="$action" method="get">
                <input type="search" name="q" placeholder="Buscar con ${engine.name}"
                       autocomplete="off" enterkeyhint="search">
              </form>
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
}
