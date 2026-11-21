package ar.liviano.browser

import org.mozilla.geckoview.GeckoSession

class Tab(val session: GeckoSession) {
    var url: String = ""
    var title: String = ""
    var canGoBack = false
    var canGoForward = false
    var loading = false
    var progress = 0
    var desktop = false
    var badge: String? = null

    val displayTitle: String
        get() = if (StartPage.isStart(url)) "Pestaña nueva" else title.ifBlank { url.ifBlank { "Pestaña nueva" } }
}
