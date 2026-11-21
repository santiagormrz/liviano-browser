package ar.liviano.browser

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BeforeUnloadPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PopupPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RepostConfirmPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt
import java.util.Calendar

/**
 * Diálogos que piden las páginas: alert/confirm/prompt, <select>, subir archivos,
 * fechas, autenticación HTTP. Sin esto, muchos sitios quedan "trabados".
 */
class Prompts(private val a: MainActivity) : PromptDelegate {

    /** GeckoResult que se completa una sola vez aunque el diálogo dispare varios eventos. */
    private class Once {
        val result = GeckoResult<PromptResponse>()
        private var done = false
        fun complete(r: () -> PromptResponse) {
            if (!done) { done = true; result.complete(r()) }
        }
    }

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun field(value: String?, password: Boolean = false, hint: String? = null) = EditText(a).apply {
        setText(value ?: "")
        this.hint = hint
        isSingleLine = true
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun wrap(vararg views: android.view.View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        views.forEach { addView(it) }
    }

    override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt): GeckoResult<PromptResponse>? {
        val o = Once()
        AlertDialog.Builder(a)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { o.complete { prompt.dismiss() } }
            .show()
        return o.result
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt): GeckoResult<PromptResponse>? {
        val o = Once()
        AlertDialog.Builder(a)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ -> o.complete { prompt.confirm(ButtonPrompt.Type.POSITIVE) } }
            .setNegativeButton(android.R.string.cancel) { _, _ -> o.complete { prompt.confirm(ButtonPrompt.Type.NEGATIVE) } }
            .setOnDismissListener { o.complete { prompt.dismiss() } }
            .show()
        return o.result
    }

    override fun onTextPrompt(session: GeckoSession, prompt: TextPrompt): GeckoResult<PromptResponse>? {
        val o = Once()
        val input = field(prompt.defaultValue)
        AlertDialog.Builder(a)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setView(wrap(input))
            .setPositiveButton(android.R.string.ok) { _, _ -> o.complete { prompt.confirm(input.text.toString()) } }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { o.complete { prompt.dismiss() } }
            .show()
        return o.result
    }

    override fun onAuthPrompt(session: GeckoSession, prompt: AuthPrompt): GeckoResult<PromptResponse>? {
        val o = Once()
        val opts = prompt.authOptions
        val onlyPassword = (opts.flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0
        val user = field(opts.username, hint = "Usuario")
        val pass = field(opts.password, password = true, hint = "Contraseña")
        val view = if (onlyPassword) wrap(pass) else wrap(user, pass)
        AlertDialog.Builder(a)
            .setTitle(prompt.title ?: "Iniciar sesión")
            .setMessage(prompt.message)
            .setView(view)
            .setPositiveButton("Entrar") { _, _ ->
                o.complete { if (onlyPassword) prompt.confirm(pass.text.toString())
                    else prompt.confirm(user.text.toString(), pass.text.toString()) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { o.complete { prompt.dismiss() } }
            .show()
        return o.result
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt): GeckoResult<PromptResponse>? {
        val o = Once()
        // Aplana los <optgroup> en una sola lista.
        val flat = mutableListOf<ChoicePrompt.Choice>()
        fun collect(list: Array<ChoicePrompt.Choice>?, depth: Int) {
            list?.forEach { c ->
                if (c.separator) return@forEach
                if (c.items != null) collect(c.items, depth + 1) else flat.add(c)
            }
        }
        collect(prompt.choices, 0)
        val labels = flat.map { it.label }.toTypedArray()
        val b = AlertDialog.Builder(a).setTitle(prompt.title)

        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            val checked = BooleanArray(flat.size) { flat[it].selected }
            b.setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val sel = flat.filterIndexed { i, _ -> checked[i] }.toTypedArray()
                    o.complete { prompt.confirm(sel) }
                }
                .setNegativeButton(android.R.string.cancel, null)
        } else {
            val current = flat.indexOfFirst { it.selected }
            b.setSingleChoiceItems(labels, current) { d, i ->
                if (!flat[i].disabled) {
                    o.complete { prompt.confirm(flat[i]) }
                    d.dismiss()
                }
            }
        }
        b.setOnDismissListener { o.complete { prompt.dismiss() } }.show()
        return o.result
    }

    override fun onDateTimePrompt(session: GeckoSession, prompt: DateTimePrompt): GeckoResult<PromptResponse>? {
        val o = Once()
        val c = Calendar.getInstance()
        val def = prompt.defaultValue.orEmpty()
        when (prompt.type) {
            DateTimePrompt.Type.DATE -> {
                Regex("""(\d{4})-(\d{2})-(\d{2})""").find(def)?.destructured?.let { (y, m, d) ->
                    c.set(y.toInt(), m.toInt() - 1, d.toInt())
                }
                DatePickerDialog(a, { _, y, m, d ->
                    o.complete { prompt.confirm("%04d-%02d-%02d".format(y, m + 1, d)) }
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
                    setOnDismissListener { o.complete { prompt.dismiss() } }
                }.show()
            }
            DateTimePrompt.Type.TIME -> {
                Regex("""(\d{2}):(\d{2})""").find(def)?.destructured?.let { (h, mi) ->
                    c.set(Calendar.HOUR_OF_DAY, h.toInt()); c.set(Calendar.MINUTE, mi.toInt())
                }
                TimePickerDialog(a, { _, h, mi ->
                    o.complete { prompt.confirm("%02d:%02d".format(h, mi)) }
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).apply {
                    setOnDismissListener { o.complete { prompt.dismiss() } }
                }.show()
            }
            else -> {
                // Mes, semana o fecha+hora: entrada de texto con el formato esperado.
                val hint = when (prompt.type) {
                    DateTimePrompt.Type.MONTH -> "AAAA-MM"
                    DateTimePrompt.Type.WEEK -> "AAAA-W##"
                    else -> "AAAA-MM-DDTHH:MM"
                }
                val input = field(def, hint = hint)
                AlertDialog.Builder(a)
                    .setTitle(prompt.title)
                    .setView(wrap(input))
                    .setPositiveButton(android.R.string.ok) { _, _ -> o.complete { prompt.confirm(input.text.toString()) } }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener { o.complete { prompt.dismiss() } }
                    .show()
            }
        }
        return o.result
    }

    override fun onFilePrompt(session: GeckoSession, prompt: FilePrompt): GeckoResult<PromptResponse>? {
        val o = Once()
        val mimes = prompt.mimeTypes?.filter { it.contains('/') }?.toTypedArray().orEmpty()
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimes.size == 1) mimes[0] else "*/*"
            if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            if (prompt.type == FilePrompt.Type.MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        a.pickFiles(intent) { uris: List<Uri> ->
            o.complete { when {
                    uris.isEmpty() -> prompt.dismiss()
                    uris.size == 1 || prompt.type != FilePrompt.Type.MULTIPLE -> prompt.confirm(a, uris[0])
                    else -> prompt.confirm(a, uris.toTypedArray())
                } }
        }
        return o.result
    }

    // Ventanas emergentes sin interacción del usuario: bloqueadas (casi siempre son publicidad).
    override fun onPopupPrompt(session: GeckoSession, prompt: PopupPrompt): GeckoResult<PromptResponse>? =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: BeforeUnloadPrompt): GeckoResult<PromptResponse>? {
        val o = Once()
        AlertDialog.Builder(a)
            .setTitle("¿Salir de esta página?")
            .setMessage("Es posible que los cambios no se guarden.")
            .setPositiveButton("Salir") { _, _ -> o.complete { prompt.confirm(AllowOrDeny.ALLOW) } }
            .setNegativeButton("Quedarse") { _, _ -> o.complete { prompt.confirm(AllowOrDeny.DENY) } }
            .setOnDismissListener { o.complete { prompt.confirm(AllowOrDeny.DENY) } }
            .show()
        return o.result
    }

    override fun onRepostConfirmPrompt(session: GeckoSession, prompt: RepostConfirmPrompt): GeckoResult<PromptResponse>? {
        val o = Once()
        AlertDialog.Builder(a)
            .setTitle("¿Reenviar formulario?")
            .setMessage("Esta página vuelve a enviar datos que ya mandaste.")
            .setPositiveButton("Reenviar") { _, _ -> o.complete { prompt.confirm(AllowOrDeny.ALLOW) } }
            .setNegativeButton(android.R.string.cancel) { _, _ -> o.complete { prompt.confirm(AllowOrDeny.DENY) } }
            .setOnDismissListener { o.complete { prompt.confirm(AllowOrDeny.DENY) } }
            .show()
        return o.result
    }
}
