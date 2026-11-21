package ar.liviano.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val runtime: GeckoRuntime get() = App.instance.runtime
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var toolbar: View
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var geckoView: GeckoView
    private lateinit var tabsCount: TextView
    private lateinit var ublockIcon: ImageView
    private lateinit var ublockBadge: TextView

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null
    private var fullscreen = false
    private lateinit var prompts: Prompts

    private var ublock: WebExtension? = null
    private var ublockAction: WebExtension.Action? = null
    private var popupDialog: Dialog? = null

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        geckoView = findViewById(R.id.geckoView)
        tabsCount = findViewById(R.id.tabsCount)
        ublockIcon = findViewById(R.id.ublockIcon)
        ublockBadge = findViewById(R.id.ublockBadge)
        prompts = Prompts(this)

        setupEdgeToEdge()
        setupToolbar()
        setupBack()

        if (isNight()) geckoView.coverUntilFirstPaint(getColor(R.color.page))

        App.instance.whenUblockReady { ext -> main.post { onUblockReady(ext) } }

        if (!handleIntent(intent)) addTab(StartPage.url())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (isFinishing) {
            geckoView.releaseSession()
            tabs.forEach { it.session.close() }
            tabs.clear()
        }
        super.onDestroy()
    }

    /** Abre enlaces que otras apps mandan al navegador. Devuelve true si abrió algo. */
    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val target = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")?.let { toUrl(it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { toUrl(it) }
            else -> null
        } ?: return false
        addTab(target)
        return true
    }

    // ------------------------------------------------------------------ interfaz

    private fun setupEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (fullscreen) {
                v.setPadding(0, 0, 0, 0)
            } else if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private fun setupToolbar() {
        urlBar.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                load(toUrl(urlBar.text.toString()))
                true
            } else false
        }
        urlBar.setOnFocusChangeListener { _, focused -> if (!focused) updateUrlBar() }

        findViewById<View>(R.id.tabsButton).setOnClickListener { showTabs() }
        findViewById<View>(R.id.tabsButton).setOnLongClickListener { addTab(StartPage.url()); true }
        findViewById<View>(R.id.ublockButton).setOnClickListener { openUblockPopup() }
        findViewById<View>(R.id.ublockButton).setOnLongClickListener { openUblockSettings(); true }
        findViewById<ImageButton>(R.id.menuButton).setOnClickListener { showMenu(it) }
    }

    private fun setupBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
    }

    @Deprecated("Solo para Android 12 o anterior")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        val tab = current
        when {
            fullscreen -> tab?.session?.exitFullScreen()
            urlBar.hasFocus() -> { urlBar.clearFocus(); hideKeyboard() }
            tab != null && tab.canGoBack -> tab.session.goBack()
            tabs.size > 1 && tab != null -> closeTab(tab)
            else -> moveTaskToBack(true)
        }
    }

    private fun updateUrlBar() {
        if (urlBar.hasFocus()) return
        urlBar.setText(current?.url?.takeUnless { it == "about:blank" || StartPage.isStart(it) } ?: "")
    }

    private fun updateProgress() {
        val tab = current ?: return
        if (tab.loading && tab.progress < 100) {
            progress.visibility = View.VISIBLE
            progress.progress = tab.progress
        } else {
            progress.visibility = View.INVISIBLE
        }
    }

    private fun updateBadge() {
        val badge = current?.badge?.takeIf { it.isNotBlank() } ?: ublockAction?.badgeText?.takeIf { it.isNotBlank() }
        ublockBadge.visibility = if (badge != null) View.VISIBLE else View.GONE
        ublockBadge.text = badge
    }

    private fun updateUi() {
        tabsCount.text = if (tabs.size > 99) ":D" else tabs.size.toString()
        updateUrlBar()
        updateProgress()
        updateBadge()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ navegación

    /** Convierte lo escrito en la barra en una dirección o una búsqueda. */
    private fun toUrl(input: String): String {
        val t = input.trim()
        return when {
            t.isEmpty() -> StartPage.url()
            Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t) -> t
            t.startsWith("about:") || t.startsWith("data:") || t.startsWith("javascript:") -> t
            !t.contains(' ') && (t.startsWith("localhost") || Regex("""^[^\s/]+\.[^\s/]{2,}""").containsMatchIn(t)) -> "https://$t"
            else -> SearchEngines.searchUrl(t)
        }
    }

    private fun load(url: String) {
        val tab = current ?: addTab(null)
        tab.session.loadUri(url)
        tab.url = url
        urlBar.clearFocus()
        hideKeyboard()
        geckoView.requestFocus()
        updateUrlBar()
    }

    // ------------------------------------------------------------------ pestañas

    private fun addTab(url: String?, open: Boolean = true, select: Boolean = true,
                       desktop: Boolean = Prefs.desktopByDefault): Tab {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .allowJavascript(true)
            .build()
        val tab = Tab(GeckoSession(settings))
        tab.desktop = desktop
        applyUserAgent(tab)
        setupDelegates(tab)
        tabs.add(tab)
        if (open) {
            tab.session.open(runtime)
            if (url != null) { tab.session.loadUri(url); tab.url = url }
            if (select) selectTab(tab)
        } else if (select) {
            // GeckoView abre la sesión por su cuenta; la mostramos en cuanto esté lista.
            selectWhenOpen(tab)
        }
        updateUi()
        return tab
    }

    private fun selectWhenOpen(tab: Tab, attempts: Int = 0) {
        when {
            tab !in tabs -> Unit
            tab.session.isOpen -> selectTab(tab)
            attempts < 100 -> main.postDelayed({ selectWhenOpen(tab, attempts + 1) }, 20)
        }
    }

    private fun selectTab(tab: Tab) {
        if (!tab.session.isOpen) { selectWhenOpen(tab); return }
        val old = current
        if (old != null && old !== tab) {
            old.session.setActive(false)
            runtime.webExtensionController.setTabActive(old.session, false)
        }
        if (geckoView.session !== tab.session) {
            geckoView.releaseSession()
            geckoView.setSession(tab.session)
        }
        current = tab
        tab.session.setActive(true)
        runtime.webExtensionController.setTabActive(tab.session, true)
        updateUi()
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        if (tab === current) {
            geckoView.releaseSession()
            current = null
        }
        tabs.removeAt(index)
        tab.session.close()
        when {
            tabs.isEmpty() -> addTab(StartPage.url())
            current == null -> selectTab(tabs[(index - 1).coerceAtLeast(0)])
        }
        updateUi()
    }

    private fun tabFor(session: GeckoSession) = tabs.find { it.session === session }

    private fun showTabs() {
        lateinit var dialog: AlertDialog
        val pad = (12 * resources.displayMetrics.density).toInt()
        val adapter = object : BaseAdapter() {
            override fun getCount() = tabs.size
            override fun getItem(position: Int) = tabs[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tab = tabs[position]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(pad * 2, pad / 2, pad / 2, pad / 2)
                }
                val text = TextView(this@MainActivity).apply {
                    text = tab.displayTitle
                    isSingleLine = true
                    textSize = 15f
                    setTextColor(getColor(R.color.ink))
                    if (tab === current) setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val close = ImageButton(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    setBackgroundResource(android.R.color.transparent)
                    isFocusable = false
                    contentDescription = "Cerrar pestaña"
                    setOnClickListener {
                        closeTab(tab)
                        notifyDataSetChanged()
                        dialog.setTitle("Pestañas (${tabs.size})")
                    }
                }
                row.addView(text)
                row.addView(close)
                row.setOnClickListener { selectTab(tab); dialog.dismiss() }
                return row
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Pestañas (${tabs.size})")
            .setAdapter(adapter, null)
            .setPositiveButton("Nueva pestaña") { _, _ -> addTab(StartPage.url()) }
            .setNegativeButton("Cerrar", null)
            .create()
        dialog.show()
    }

    // ------------------------------------------------------------------ agente de usuario y escritorio

    private fun applyUserAgent(tab: Tab) {
        val s = tab.session.settings
        var override = UserAgents.override()
        val uaIsDesktop = UserAgents.isDesktop()
        // En vista de escritorio, un agente "móvil" elegido de la lista se reemplaza por el de escritorio de Gecko.
        if (tab.desktop && !uaIsDesktop && Prefs.userAgent != UserAgents.CUSTOM) override = null
        s.userAgentOverride = override
        s.userAgentMode = if (tab.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                          else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        s.viewportMode = if (tab.desktop || uaIsDesktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                         else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
    }

    private fun toggleDesktop() {
        val tab = current ?: return
        tab.desktop = !tab.desktop
        applyUserAgent(tab)
        tab.session.reload()
        toast(if (tab.desktop) "Vista de escritorio activada" else "Vista móvil")
    }

    private fun chooseUserAgent() {
        val agents = UserAgents.all
        val selected = agents.indexOfFirst { it.key == Prefs.userAgent }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Agente de usuario")
            .setSingleChoiceItems(agents.map { it.label }.toTypedArray(), selected) { d, i ->
                d.dismiss()
                if (agents[i].key == UserAgents.CUSTOM) editCustomUserAgent()
                else { Prefs.userAgent = agents[i].key; reapplyUserAgent() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editCustomUserAgent() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(Prefs.customUserAgent.ifBlank { UserAgents.override() ?: "" })
            hint = "Mozilla/5.0 (...)"
            minLines = 3
        }
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Agente de usuario personalizado")
            .setView(box)
            .setPositiveButton("Usar") { _, _ ->
                Prefs.customUserAgent = input.text.toString().trim()
                Prefs.userAgent = UserAgents.CUSTOM
                reapplyUserAgent()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** El agente de usuario es global: se aplica a todas las pestañas y se recarga la actual. */
    private fun reapplyUserAgent() {
        tabs.forEach { applyUserAgent(it) }
        current?.session?.reload()
        toast("Agente: ${UserAgents.current.label}")
    }

    // ------------------------------------------------------------------ accesos directos

    private fun setShortcut(index: Int, shortcut: Shortcut?) {
        Prefs.shortcuts = Prefs.shortcuts.toMutableList().also { it[index] = shortcut }
        refreshStartPages()
    }

    /** Vuelve a generar la pantalla de inicio en las pestañas que la muestran. */
    private fun refreshStartPages() {
        val url = StartPage.url()
        tabs.filter { StartPage.isStart(it.url) }.forEach { tab ->
            tab.session.load(GeckoSession.Loader().uri(url).flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY))
            tab.url = url
        }
    }

    private fun editShortcut(index: Int, suggestion: Shortcut? = null) {
        val existing = Prefs.shortcuts.getOrNull(index)
        val start = suggestion ?: existing
        val pad = (20 * resources.displayMetrics.density).toInt()
        val name = EditText(this).apply {
            hint = "Nombre (opcional)"; isSingleLine = true; setText(start?.name ?: "")
        }
        val address = EditText(this).apply {
            hint = "Dirección, p. ej. instagram.com"; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(start?.url ?: "")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(name); addView(address)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Agregar acceso directo" else "Editar acceso directo")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val raw = address.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(raw)) raw else "https://$raw"
                setShortcut(index, Shortcut(name.text.toString().trim(), url))
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) builder.setNeutralButton("Quitar") { _, _ -> setShortcut(index, null) }
        builder.show()
        if (start == null) address.requestFocus()
    }

    /** Agrega la página actual en el primer lugar libre de la pantalla de inicio. */
    private fun addCurrentToStart() {
        val tab = current ?: return
        if (tab.url.isBlank() || StartPage.isStart(tab.url)) { toast("Abrí una página primero"); return }
        val free = Prefs.shortcuts.indexOfFirst { it == null }
        if (free < 0) { toast("Los 8 accesos están ocupados: quitá uno desde la pantalla de inicio"); return }
        val host = Uri.parse(tab.url).host?.removePrefix("www.") ?: ""
        editShortcut(free, Shortcut(tab.title.take(24).ifBlank { host }, tab.url))
    }

    // ------------------------------------------------------------------ menú

    private fun showMenu(anchor: View) {
        val tab = current
        val menu = PopupMenu(this, anchor)
        val m = menu.menu
        m.add(0, 1, 0, "Atrás").isEnabled = tab?.canGoBack == true
        m.add(0, 2, 0, "Adelante").isEnabled = tab?.canGoForward == true
        m.add(0, 3, 0, if (tab?.loading == true) "Detener" else "Recargar")
        m.add(0, 4, 0, "Nueva pestaña")
        m.add(0, 5, 0, "Vista de escritorio").apply { isCheckable = true; isChecked = tab?.desktop == true }
        m.add(0, 6, 0, "Agente de usuario…")
        m.add(0, 7, 0, "uBlock Origin")
        m.add(0, 8, 0, "Configuración de uBlock Origin")
        m.add(0, 9, 0, "Compartir")
        m.add(0, 12, 0, "Agregar a la pantalla de inicio")
        m.add(0, 10, 0, "Ajustes…")
        m.add(0, 11, 0, "Salir")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> tab?.session?.goBack()
                2 -> tab?.session?.goForward()
                3 -> if (tab?.loading == true) tab.session.stop() else tab?.session?.reload()
                4 -> { addTab(StartPage.url()); urlBar.requestFocus() }
                5 -> toggleDesktop()
                6 -> chooseUserAgent()
                7 -> openUblockPopup()
                8 -> openUblockSettings()
                9 -> tab?.url?.takeUnless { StartPage.isStart(it) }?.let { share(it, tab.title) }
                10 -> showSettings()
                11 -> finishAndRemoveTask()
                12 -> addCurrentToStart()
            }
            true
        }
        menu.show()
    }

    private fun share(url: String, title: String?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivity(Intent.createChooser(send, "Compartir"))
    }

    private fun showSettings() {
        val items = arrayOf(
            "Buscador: ${SearchEngines.current.name}",
            "Pestañas nuevas en vista de escritorio: ${if (Prefs.desktopByDefault) "Sí" else "No"}",
            "Agente de usuario: ${UserAgents.current.label}",
            "Borrar datos de navegación",
        )
        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> chooseEngine()
                    1 -> { Prefs.desktopByDefault = !Prefs.desktopByDefault; showSettings() }
                    2 -> chooseUserAgent()
                    3 -> clearData()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun chooseEngine() {
        val engines = SearchEngines.all
        AlertDialog.Builder(this)
            .setTitle("Buscador")
            .setSingleChoiceItems(engines.map { it.name }.toTypedArray(),
                engines.indexOf(SearchEngines.current)) { d, i ->
                Prefs.engine = engines[i].key
                d.dismiss()
                refreshStartPages()
            }
            .show()
    }

    private fun clearData() {
        AlertDialog.Builder(this)
            .setTitle("Borrar datos de navegación")
            .setMessage("Se borran cookies, caché, sesiones iniciadas y permisos de sitios. La configuración de uBlock Origin se conserva.")
            .setPositiveButton("Borrar") { _, _ ->
                val flags = StorageController.ClearFlags.COOKIES or
                    StorageController.ClearFlags.NETWORK_CACHE or
                    StorageController.ClearFlags.IMAGE_CACHE or
                    StorageController.ClearFlags.AUTH_SESSIONS or
                    StorageController.ClearFlags.PERMISSIONS or
                    StorageController.ClearFlags.SITE_SETTINGS
                runtime.storageController.clearData(flags).accept { main.post { toast("Datos borrados") } }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ uBlock Origin

    private val ublockActionDelegate = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            if (session == null) {
                ublockAction = action
                action.icon?.getBitmap((24 * resources.displayMetrics.density).toInt())?.accept { bmp ->
                    if (bmp != null) main.post { ublockIcon.setImageBitmap(bmp) }
                }
            } else {
                tabFor(session)?.badge = action.badgeText
            }
            updateBadge()
        }

        override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? {
            val popup = GeckoSession()
            popup.open(runtime)
            showUblockPopup(popup)
            return GeckoResult.fromValue(popup)
        }
    }

    private val ublockTabDelegate = object : WebExtension.TabDelegate {
        override fun onNewTab(source: WebExtension, createDetails: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
            popupDialog?.dismiss()
            val tab = addTab(null, open = false, select = createDetails.active != false, desktop = false)
            return GeckoResult.fromValue(tab.session)
        }
    }

    private val ublockSessionTabDelegate = object : WebExtension.SessionTabDelegate {
        override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> {
            tabFor(session)?.let { closeTab(it) }
            return GeckoResult.allow()
        }
    }

    private fun onUblockReady(ext: WebExtension) {
        ublock = ext
        ext.setActionDelegate(ublockActionDelegate)
        ext.setTabDelegate(ublockTabDelegate)
        tabs.forEach { registerUblock(it) }
    }

    private fun registerUblock(tab: Tab) {
        val ext = ublock ?: return
        tab.session.webExtensionController.setActionDelegate(ext, ublockActionDelegate)
        tab.session.webExtensionController.setTabDelegate(ext, ublockSessionTabDelegate)
    }

    private fun openUblockPopup() {
        val action = ublockAction
        if (action == null) { toast("uBlock Origin se está iniciando…"); return }
        action.click()
    }

    private fun openUblockSettings() {
        val url = ublock?.metaData?.optionsPageUrl
        if (url == null) { toast("uBlock Origin se está iniciando…"); return }
        addTab(url, desktop = false)
    }

    private fun showUblockPopup(popup: GeckoSession) {
        popupDialog?.dismiss()
        val view = GeckoView(this)
        view.setSession(popup)
        popup.promptDelegate = prompts
        val dialog = Dialog(this)
        popup.contentDelegate = object : ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) { dialog.dismiss() }
        }
        val height = (resources.displayMetrics.heightPixels * 0.72).toInt()
        dialog.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.setOnDismissListener {
            view.releaseSession()
            popup.close()
            if (popupDialog === dialog) popupDialog = null
        }
        popupDialog = dialog
        dialog.show()
    }

    // ------------------------------------------------------------------ delegados de cada pestaña

    private fun setupDelegates(tab: Tab) {
        val s = tab.session
        s.promptDelegate = prompts
        s.permissionDelegate = permissionDelegate
        registerUblock(tab)

        s.navigationDelegate = object : NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?,
                                          perms: MutableList<PermissionDelegate.ContentPermission>,
                                          hasUserGesture: Boolean) {
                tab.url = url ?: ""
                if (tab === current) updateUrlBar()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canGoBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canGoForward = canGoForward }

            override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                StartPage.editIndex(request.uri)?.let { i ->
                    main.post { editShortcut(i) }
                    return GeckoResult.deny()
                }
                val scheme = Uri.parse(request.uri).scheme?.lowercase() ?: return GeckoResult.allow()
                if (scheme in INTERNAL_SCHEMES) return GeckoResult.allow()
                main.post { openExternal(request.uri) }
                return GeckoResult.deny()
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val newTab = addTab(null, open = false, select = true, desktop = tab.desktop)
                newTab.url = uri
                return GeckoResult.fromValue(newTab.session)
            }
        }

        s.progressDelegate = object : ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.loading = true; tab.progress = 5; tab.badge = null
                if (tab === current) { updateProgress(); updateBadge() }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.loading = false; tab.progress = 100
                if (tab === current) updateProgress()
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                tab.progress = progress
                if (tab === current) updateProgress()
            }
        }

        s.contentDelegate = object : ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { tab.title = title ?: "" }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) { setFullscreen(fullScreen) }

            override fun onCloseRequest(session: GeckoSession) { closeTab(tab) }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) { download(response) }

            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int,
                                       element: ContentDelegate.ContextElement) {
                showContextMenu(element, tab)
            }

            override fun onCrash(session: GeckoSession) { recover(tab) }
            override fun onKill(session: GeckoSession) { recover(tab) }
        }
    }

    private fun recover(tab: Tab) {
        if (!tab.session.isOpen) tab.session.open(runtime)
        if (tab === current) {
            geckoView.releaseSession()
            geckoView.setSession(tab.session)
        }
        if (tab.url.isNotBlank()) tab.session.loadUri(tab.url)
    }

    private fun openExternal(uri: String) {
        try {
            val intent = if (uri.startsWith("intent:")) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                         else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Enlaces "intent:" suelen traer una página web de reserva.
            val fallback = runCatching {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            }.getOrNull()
            if (fallback != null) load(fallback) else toast("Ninguna app puede abrir este enlace")
        } catch (e: Exception) {
            toast("Enlace no válido")
        }
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        toolbar.visibility = if (on) View.GONE else View.VISIBLE
        progress.visibility = if (on) View.GONE else View.INVISIBLE
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { c ->
                if (on) {
                    c.hide(WindowInsets.Type.systemBars())
                    c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else c.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on)
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            else 0
        }
        root.requestApplyInsets()
        if (!on) updateProgress()
    }

    private fun showContextMenu(el: ContentDelegate.ContextElement, from: Tab) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val link = el.linkUri
        val src = el.srcUri
        // Accesos directos de la pantalla de inicio
        if (link != null && StartPage.isStart(from.url)) {
            StartPage.editIndex(link)?.let { editShortcut(it); return }
            val index = Prefs.shortcuts.indexOfFirst { it?.url == link }
            if (index >= 0) {
                actions += "Editar acceso directo" to { editShortcut(index) }
                actions += "Quitar acceso directo" to { setShortcut(index, null) }
            }
        }
        if (link != null) {
            actions += "Abrir en pestaña nueva" to { addTab(link, desktop = from.desktop); Unit }
            actions += "Abrir en segundo plano" to { addTab(link, select = false, desktop = from.desktop); toast("Abierto en segundo plano") }
            actions += "Copiar enlace" to { copy(link) }
            actions += "Compartir enlace" to { share(link, el.title) }
            actions += "Descargar enlace" to { downloadUrl(link) }
        }
        if (src != null && el.type == ContentDelegate.ContextElement.TYPE_IMAGE) {
            actions += "Abrir imagen" to { addTab(src, desktop = from.desktop); Unit }
            actions += "Descargar imagen" to { downloadUrl(src) }
            actions += "Copiar dirección de la imagen" to { copy(src) }
        } else if (src != null && (el.type == ContentDelegate.ContextElement.TYPE_VIDEO ||
                                   el.type == ContentDelegate.ContextElement.TYPE_AUDIO)) {
            actions += "Descargar archivo" to { downloadUrl(src) }
            actions += "Copiar dirección" to { copy(src) }
        }
        if (actions.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(link ?: src)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("enlace", text))
        if (Build.VERSION.SDK_INT < 33) toast("Copiado")
    }

    // ------------------------------------------------------------------ descargas

    /** Descarga lo que Gecko no puede mostrar, reutilizando la misma respuesta (cookies, sesión, POST). */
    private fun download(response: WebResponse) {
        val headers = response.headers.mapKeys { it.key.lowercase() }
        val mime = headers["content-type"]?.substringBefore(';')?.trim()?.ifEmpty { null }
        val name = URLUtil.guessFileName(response.uri, headers["content-disposition"], mime)
        val body = response.body
        if (body == null) { downloadUrl(response.uri); return }
        toast("Descargando $name")
        thread(name = "descarga") {
            val resolver = contentResolver
            var target: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("No se pudo crear el archivo")
                resolver.openOutputStream(target)!!.use { out -> body.use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(target, values, null, null)
                main.post { toast("Descargado en Descargas: $name") }
            } catch (e: Exception) {
                target?.let { runCatching { resolver.delete(it, null, null) } }
                main.post { toast("Error al descargar $name") }
            }
        }
    }

    /** Descargas iniciadas desde el menú contextual. */
    private fun downloadUrl(url: String) {
        if (!url.startsWith("http")) { toast("Este archivo no se puede descargar directamente"); return }
        try {
            val name = URLUtil.guessFileName(url, null, null)
            val request = DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            UserAgents.override()?.let { request.addRequestHeader("User-Agent", it) }
            getSystemService(DownloadManager::class.java).enqueue(request)
            toast("Descargando $name")
        } catch (e: Exception) {
            toast("No se pudo descargar")
        }
    }

    // ------------------------------------------------------------------ permisos

    private var androidPermissionCallback: PermissionDelegate.Callback? = null

    private val permissionDelegate = object : PermissionDelegate {
        override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?,
                                                 callback: PermissionDelegate.Callback) {
            val missing = permissions.orEmpty().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) { callback.grant(); return }
            androidPermissionCallback = callback
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
        }

        override fun onContentPermissionRequest(session: GeckoSession,
                                                perm: PermissionDelegate.ContentPermission): GeckoResult<Int>? {
            val allow = PermissionDelegate.ContentPermission.VALUE_ALLOW
            val deny = PermissionDelegate.ContentPermission.VALUE_DENY
            val what = when (perm.permission) {
                PermissionDelegate.PERMISSION_GEOLOCATION -> "conocer tu ubicación"
                PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "reproducir contenido protegido (DRM)"
                PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> return GeckoResult.fromValue(allow)
                PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> return GeckoResult.fromValue(allow)
                else -> return GeckoResult.fromValue(deny)
            }
            return ask("${host(perm.uri)} quiere $what").map { if (it == true) allow else deny }
        }

        override fun onMediaPermissionRequest(session: GeckoSession, uri: String,
                                              video: Array<out PermissionDelegate.MediaSource>?,
                                              audio: Array<out PermissionDelegate.MediaSource>?,
                                              callback: PermissionDelegate.MediaCallback) {
            val parts = listOfNotNull(
                if (!video.isNullOrEmpty()) "la cámara" else null,
                if (!audio.isNullOrEmpty()) "el micrófono" else null,
            )
            if (parts.isEmpty()) { callback.reject(); return }
            ask("${host(uri)} quiere usar ${parts.joinToString(" y ")}").accept { ok ->
                if (ok == true) callback.grant(video?.firstOrNull(), audio?.firstOrNull()) else callback.reject()
            }
        }
    }

    private fun host(uri: String) = Uri.parse(uri).host ?: uri

    private fun ask(message: String): GeckoResult<Boolean> {
        val result = GeckoResult<Boolean>()
        var done = false
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Permitir") { _, _ -> if (!done) { done = true; result.complete(true) } }
            .setNegativeButton("Bloquear") { _, _ -> if (!done) { done = true; result.complete(false) } }
            .setOnDismissListener { if (!done) { done = true; result.complete(false) } }
            .show()
        return result
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        val cb = androidPermissionCallback ?: return
        androidPermissionCallback = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) cb.grant() else cb.reject()
    }

    // ------------------------------------------------------------------ selector de archivos

    private var filePickerCallback: ((List<Uri>) -> Unit)? = null

    fun pickFiles(intent: Intent, callback: (List<Uri>) -> Unit) {
        filePickerCallback?.invoke(emptyList())
        filePickerCallback = callback
        try {
            startActivityForResult(intent, REQ_FILES)
        } catch (e: ActivityNotFoundException) {
            filePickerCallback = null
            callback(emptyList())
            toast("No hay app para elegir archivos")
        }
    }

    @Deprecated("API clásica de Activity; suficiente para este caso")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILES) return
        val cb = filePickerCallback ?: return
        filePickerCallback = null
        val uris = mutableListOf<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
            else data.data?.let { uris += it }
        }
        cb(uris)
    }

    companion object {
        private const val REQ_PERMISSIONS = 10
        private const val REQ_FILES = 11
        private val INTERNAL_SCHEMES = setOf(
            "http", "https", "about", "data", "blob", "file", "javascript",
            "moz-extension", "resource", "view-source"
        )
    }
}
