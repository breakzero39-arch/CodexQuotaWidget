package com.codex.quota.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.GlanceId
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.codex.quota.QuotaApp
import com.codex.quota.R
import com.codex.quota.data.BonusQuota
import com.codex.quota.data.CodexQuota
import com.codex.quota.data.QuotaState
import com.codex.quota.data.state
import com.codex.quota.ui.MainActivity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class CodexQuotaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = (context.applicationContext as QuotaApp).container.store
        // Any failure here would leave the widget on the (empty) loading placeholder forever,
        // so every read is guarded — the worst case renders the visible disconnected card.
        val appWidgetId = runCatching { (id as AppWidgetId).appWidgetId }
            .getOrDefault(android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID)
        val accountId = if (appWidgetId != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
            runCatching { store.accountForWidget(appWidgetId) }.getOrNull()
        } else null
        val account = accountId?.let { runCatching { store.accountNow(it) }.getOrNull() }
        val quota = accountId?.let { runCatching { store.currentQuota(it) }.getOrNull() }?.toQuota()
        val sessionExpired = accountId?.let { runCatching { store.sessionExpiredNow(it) }.getOrDefault(true) } ?: true
        provideContent {
            CodexQuotaWidgetContent(
                quota = quota,
                sessionExpired = sessionExpired,
                displayName = account?.displayName,
                accountBound = accountId != null
            )
        }
    }
}

@Composable
internal fun CodexQuotaWidgetContent(
    quota: CodexQuota?,
    sessionExpired: Boolean,
    displayName: String?,
    accountBound: Boolean
) {
    val size = LocalSize.current
    // 4x2 is ~155dp tall on this launcher; treat anything below 4x3 as compact so the
    // multi-line ExpandedBody never clips off the bottom of the card.
    val compact = size.height < 190.dp
    // Tap → open the app. Refresh lives in the app ("Refresh All") and the periodic worker.
    val onClick = actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))
    QuotaCard(
        quota = quota,
        sessionExpired = sessionExpired,
        displayName = displayName,
        accountBound = accountBound,
        compact = compact,
        onClick = onClick
    )
}

// ---------- colors ----------

private val Background = Color(0xFF0D0D0F)
private val TextPrimary = Color(0xFFF5F5F7)
private val TextSecondary = Color(0xFF8E8E93)
private val SegmentActive = Color(0xFFF2F2F2)
private val SegmentInactive = Color(0xFF242426)
private val Accent = Color(0xFFFFD60A)

private fun cp(color: Color): ColorProvider = ColorProvider(color)

private fun connectionColor(connected: Boolean): Color =
    if (connected) Color(0xFF30D158) else Color(0xFFFF453A)

private fun resolveState(quota: CodexQuota?): QuotaState {
    if (quota == null) return QuotaState.ERROR
    val stale = Duration.between(quota.updatedAt, Instant.now()) > Duration.ofHours(2)
    return if (stale) QuotaState.STALE else quota.state()
}

// ---------- card ----------

@Composable
private fun QuotaCard(
    quota: CodexQuota?,
    sessionExpired: Boolean,
    displayName: String?,
    accountBound: Boolean,
    compact: Boolean,
    onClick: Action
) {
    val state = resolveState(quota)
    val connected = accountBound && quota != null && state != QuotaState.STALE && !sessionExpired
    val low = state == QuotaState.LOW || state == QuotaState.CRITICAL
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(cp(Background))
            .cornerRadius(28.dp)
            .clickable(onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            !accountBound -> UnboundCard(compact)
            quota == null -> EmptyCard()
            else -> Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Header(connected, low, displayName)
                if (compact) CompactBody(quota) else ExpandedBody(quota)
            }
        }
    }
}

@Composable
private fun Header(connected: Boolean, low: Boolean, displayName: String?) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (displayName != null) "Codex · $displayName" else "Codex",
            style = TextStyle(color = cp(TextSecondary), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        )
        Spacer(GlanceModifier.width(10.dp))
        ConnectionBadge(connected, low)
    }
}

@Composable
private fun UnboundCard(compact: Boolean) {
    // Structurally mirrors the bound bodies: the launcher renders cards with the Header row
    // + big text + Gauge bitmap, but showed nothing for a centered Column without a bitmap.
    // Same skeleton here so an unbound widget renders the same way the bound cards do.
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Header(connected = false, low = false, displayName = null)
        Spacer(GlanceModifier.height(if (compact) 8.dp else 12.dp))
        Text(
            text = "--",
            style = TextStyle(
                color = cp(TextPrimary),
                fontSize = if (compact) 40.sp else 50.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = "Account disconnected",
            style = TextStyle(color = cp(TextSecondary), fontSize = 13.sp)
        )
        Spacer(GlanceModifier.height(if (compact) 8.dp else 12.dp))
        Gauge(percent = 0f, height = if (compact) 20.dp else 32.dp)
        Spacer(GlanceModifier.height(if (compact) 8.dp else 12.dp))
        Text(
            text = "Tap to select account",
            style = TextStyle(color = cp(TextSecondary), fontSize = 12.sp)
        )
    }
}

@Composable
private fun ConnectionBadge(connected: Boolean, low: Boolean) {
    val color = connectionColor(connected)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (connected && low) {
            // Low quota: the Live dot breathes (size pulses) via a host-side ViewFlipper animation,
            // which runs in the launcher process without consuming widget-update budget.
            val rv = RemoteViews(LocalContext.current.packageName, R.layout.widget_live_dot)
            AndroidRemoteViews(rv, GlanceModifier.size(12.dp))
        } else {
            Box(
                modifier = GlanceModifier
                    .size(8.dp)
                    .background(cp(color))
                    .cornerRadius(4.dp)
            ) { }
        }
        Spacer(GlanceModifier.width(5.dp))
        Text(
            text = if (connected) "Live" else "Disconnected",
            style = TextStyle(color = cp(color), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun CompactBody(quota: CodexQuota) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Spacer(GlanceModifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${quota.remainingPercent.roundToInt()}%",
                style = TextStyle(color = cp(TextPrimary), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = "剩余",
                style = TextStyle(color = cp(TextSecondary), fontSize = 12.sp)
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        Gauge(quota.remainingPercent, height = 16.dp)
        Spacer(GlanceModifier.height(6.dp))
        Text(
            text = countdown(quota.resetAt),
            style = TextStyle(color = cp(TextPrimary), fontSize = 18.sp, fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun ExpandedBody(quota: CodexQuota) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = "${quota.remainingPercent.roundToInt()}%",
            style = TextStyle(color = cp(TextPrimary), fontSize = 44.sp, fontWeight = FontWeight.Bold)
        )
        Text(
            text = "本周期剩余",
            style = TextStyle(color = cp(TextSecondary), fontSize = 13.sp)
        )
        Spacer(GlanceModifier.height(10.dp))
        Gauge(quota.remainingPercent, height = 28.dp)
        Spacer(GlanceModifier.height(10.dp))
        Text(
            text = "距离下次重置",
            style = TextStyle(color = cp(TextSecondary), fontSize = 12.sp)
        )
        Text(
            text = countdown(quota.resetAt),
            style = TextStyle(color = cp(TextPrimary), fontSize = 22.sp, fontWeight = FontWeight.Medium)
        )
        Text(
            text = formatReset(quota.resetAt),
            style = TextStyle(color = cp(TextSecondary), fontSize = 12.sp)
        )
        quota.bonus?.let { bonus ->
            Spacer(GlanceModifier.height(8.dp))
            BonusRow(bonus)
        }
    }
}

// Height profile (max = peak): plateau pairs then singles — a restrained battery-meter ramp.
private val gaugeProfile = listOf(6f, 6f, 7f, 7f, 8f, 8f, 9f, 9f, 10f, 11f, 12f, 13f)

@Composable
private fun Gauge(percent: Float, height: Dp) {
    // Glance has no fractional-width modifier, so the gauge is drawn to a bitmap
    // (ImageProvider) and scaled — this keeps the precise partial-fill tail (37% → 4.44).
    val contentWidth = LocalSize.current.width - 40.dp
    val scale = 3f
    val widthPx = (contentWidth.value * scale).roundToInt().coerceAtLeast(1)
    val heightPx = (height.value * scale).roundToInt().coerceAtLeast(1)
    val bitmap = drawGauge(widthPx, heightPx, percent, height.value, scale)
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = null,
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(height)
    )
}

private fun drawGauge(widthPx: Int, heightPx: Int, percent: Float, heightDp: Float, scale: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val active = Paint().apply { isAntiAlias = true; color = SegmentActive.toArgb() }
    val inactive = Paint().apply { isAntiAlias = true; color = SegmentInactive.toArgb() }
    val peak = gaugeProfile.max()
    val exact = percent / 100f * gaugeProfile.size
    val gap = 3f * scale
    val segmentWidth = (widthPx - gap * (gaugeProfile.size - 1)) / gaugeProfile.size.toFloat()
    for (i in gaugeProfile.indices) {
        val segHeight = heightDp * gaugeProfile[i] / peak * scale
        val radius = segHeight * 0.4f
        val fill = (exact - i).coerceIn(0f, 1f)
        val left = i * (segmentWidth + gap)
        val top = heightPx - segHeight
        // Inactive base first (keeps the full 12-segment outline visible even at 0%).
        canvas.drawRoundRect(left, top, left + segmentWidth, heightPx.toFloat(), radius, radius, inactive)
        if (fill > 0f) {
            canvas.drawRoundRect(left, top, left + segmentWidth * fill, heightPx.toFloat(), radius, radius, active)
        }
    }
    return bitmap
}

@Composable
private fun BonusRow(bonus: BonusQuota) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("✦", style = TextStyle(color = cp(Accent), fontSize = 14.sp))
        Spacer(GlanceModifier.width(6.dp))
        Text(bonus.label, style = TextStyle(color = cp(TextSecondary), fontSize = 12.sp))
        bonus.amountPercent?.let {
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = "+${it.roundToInt()}%",
                style = TextStyle(color = cp(TextPrimary), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            )
        }
    }
}

@Composable
private fun EmptyCard() {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Codex",
                style = TextStyle(color = cp(TextSecondary), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            )
            Spacer(GlanceModifier.width(10.dp))
            ConnectionBadge(connected = false, low = false)
        }
        Spacer(GlanceModifier.height(8.dp))
        Text(
            text = "—",
            style = TextStyle(color = cp(TextPrimary), fontSize = 40.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(GlanceModifier.height(8.dp))
        Text(
            text = "点击刷新",
            style = TextStyle(color = cp(TextSecondary), fontSize = 12.sp)
        )
    }
}

// ---------- formatting ----------

private fun countdown(resetAt: Instant): String {
    val d = Duration.between(Instant.now(), resetAt)
    if (d.isNegative) return "已重置"
    val totalHours = d.toHours()
    val days = totalHours / 24
    val hours = totalHours % 24
    return if (days > 0) "${days}d ${hours}h" else "${totalHours}h ${d.toMinutesPart()}m"
}

private fun formatReset(resetAt: Instant): String =
    DateTimeFormatter.ofPattern("MMM d · HH:mm", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault())
        .format(resetAt)
