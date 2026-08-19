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
import com.codex.quota.QuotaApp
import com.codex.quota.data.AccountData
import com.codex.quota.work.WidgetSync
import kotlinx.coroutines.flow.first
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
                    (application as QuotaApp).container.store.bindWidget(appWidgetId, accountId)
                    // A tap-launched config has no launcher host to re-render the widget on bind;
                    // the launcher flow triggers its own update, so this extra render is harmless.
                    WidgetSync.updateForAccount(applicationContext, accountId)
                }
            } catch (t: Throwable) {
                // binding failed → widget still added, shows "Account disconnected"
            }
            runOnUiThread { resultOk() }
        }.start()
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
