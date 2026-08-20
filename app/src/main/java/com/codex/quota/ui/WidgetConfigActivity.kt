package com.codex.quota.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.codex.quota.QuotaApp
import com.codex.quota.data.AccountData
import com.codex.quota.widget.CodexQuotaWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Widget configure activity (launched by the launcher when a Codex Quota widget is added).
 *
 * Kept deliberately tiny and plain (no Compose / ViewModel / Material) because a crash
 * here silently aborts the whole "add widget" action on most launchers. Every data read
 * and binding runs off the main thread and is fully guarded — the worst case is the widget
 * still gets added and shows "Account disconnected", never a dead add flow.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(RESULT_CANCELED) // canceled unless the user picks / auto-binds an account

        Thread {
            val accounts = try {
                runBlocking { (application as QuotaApp).container.store.accountData.first() }
            } catch (t: Throwable) {
                emptyList()
            }
            runOnUiThread { showPicker(accounts) }
        }.start()
    }

    private fun showPicker(accounts: List<AccountData>) {
        when (accounts.size) {
            0 -> setContentView(buildRoot(listOf(buildMessage(), buildAction("完成") { resultOk() })))
            1 -> bindAndFinish(accounts[0].account.id)
            else -> setContentView(
                buildRoot(accounts.map { item ->
                    buildAction(item.account.displayName ?: "Account") { bindAndFinish(item.account.id) }
                })
            )
        }
    }

    private fun bindAndFinish(accountId: String) {
        Thread {
            try {
                runBlocking {
                    val container = (application as QuotaApp).container
                    container.store.bindWidget(appWidgetId, accountId)
                    // Sync quota before rendering so a freshly bound card never sits on the
                    // empty "—" placeholder (which reads like the disconnected "--").
                    try {
                        container.repository.refresh(accountId)
                    } catch (_: Exception) {
                        // keep last cached quota; the card still renders bound
                    }
                }
            } catch (t: Throwable) {
                // binding failed → widget still added, shows "Account disconnected"
            }
            // Render the freshly bound widget. A just-added widget is NOT yet bound in
            // AppWidgetManager while this config activity is on screen — the launcher binds it
            // only after we return RESULT_OK — so a direct render here throws on the first add
            // (the old code swallowed that, which is why the first bind looked dead until a
            // second pick). Poll briefly after finish until the launcher has bound + placed it.
            scheduleBoundRender()
            runOnUiThread { resultOk() }
        }.start()
    }

    /** Re-render [appWidgetId] once the launcher has bound it, retrying until it is real. */
    private fun scheduleBoundRender() {
        val manager = GlanceAppWidgetManager(applicationContext)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            delay(400) // let the launcher bind the widget after RESULT_OK
            repeat(12) { attempt ->
                val rendered = try {
                    val glanceId = manager.getGlanceIdBy(appWidgetId)
                    CodexQuotaWidget().update(applicationContext, glanceId)
                    true
                } catch (_: Throwable) {
                    false
                }
                if (rendered) return@launch
                if (attempt < 11) delay(500)
            }
        }
    }

    private fun resultOk() {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )
        finish()
    }

    // ---------- plain-View UI ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildLabel(text: String, big: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = if (big) 22f else 14f
        typeface = if (big) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        gravity = Gravity.CENTER
        setTextColor(0xFF101010.toInt())
    }

    private fun buildMessage(): TextView = TextView(this).apply {
        text = "还没有账号，请先打开 Codex Quota App 添加账号。"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(0xFF555555.toInt())
    }

    private fun buildAction(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun buildRoot(children: List<android.view.View>): ScrollView {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            addView(buildLabel("选择要绑定的账号", big = true))
            addView(
                buildLabel("这个小组件只会显示所选账号的额度"),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
        }
        children.forEach { v ->
            column.addView(
                v,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(20) }
            )
        }
        return ScrollView(this).apply { addView(column) }
    }
}
