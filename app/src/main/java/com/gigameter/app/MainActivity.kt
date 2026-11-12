package com.gigameter.app

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.DatePickerDialog
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

fun fmt(b: Long): String =
    if (b >= 1073741824L) String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    else String.format(Locale.US, "%.0f MB", b / 1048576.0)

object Store {
    private fun p(c: Context) = c.getSharedPreferences("giga", Context.MODE_PRIVATE)

    fun key(t: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    fun dayStart(t: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun raw(c: Context, type: Int, s: Long, e: Long): Long = try {
        val b = c.getSystemService(NetworkStatsManager::class.java)
            .querySummaryForDevice(type, null, s, e)
        b.rxBytes + b.txBytes
    } catch (ex: Exception) { -1L }

    fun bytes(c: Context, type: Int, s: Long, e: Long): Long = maxOf(0L, raw(c, type, s, e))

    fun sims(c: Context): List<Int> =
        p(c).getStringSet("sims", emptySet())!!.mapNotNull { it.toIntOrNull() }.sorted()

    fun simName(c: Context, id: Int): String = p(c).getString("n_$id", "خط $id") ?: "خط $id"

    fun simBytes(c: Context, id: Int, day: Long): Long = p(c).getLong("d_${id}_${key(day)}", 0L)

    fun sample(c: Context) {
        val now = System.currentTimeMillis()
        val install = try {
            c.packageManager.getPackageInfo(c.packageName, 0).firstInstallTime
        } catch (e: Exception) { now - 86400000L }
        val total = raw(c, ConnectivityManager.TYPE_MOBILE, install, now)
        if (total < 0) return
        val sp = p(c)
        val last = sp.getLong("last", -1L)
        sp.edit().putLong("last", total).apply()
        if (last < 0) return
        val delta = maxOf(0L, total - last)
        if (delta == 0L) return
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (subId < 0) return
        var name = "خط $subId"
        if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                c.getSystemService(SubscriptionManager::class.java)
                    .getActiveSubscriptionInfo(subId)?.let { name = it.displayName.toString() }
            } catch (e: SecurityException) { }
        }
        val sims = sp.getStringSet("sims", emptySet())!!.toMutableSet()
        sims.add(subId.toString())
        val k = "d_${subId}_${key(now)}"
        sp.edit().putStringSet("sims", sims).putString("n_$subId", name)
            .putLong(k, sp.getLong(k, 0L) + delta).apply()
    }
}

class SampleWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        Store.sample(applicationContext)
        return Result.success()
    }
}

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private var tab = 0
    private var from = Store.dayStart(System.currentTimeMillis())
    private var to = from

    private val simColors = intArrayOf(
        0xFF22D3EE.toInt(), 0xFFF472B6.toInt(), 0xFFFACC15.toInt(),
        0xFF4ADE80.toInt(), 0xFFA78BFA.toInt()
    )
    private val wifiColor = 0xFF3B82F6.toInt()
    private val mobColor = 0xFFF97316.toInt()
    private val totalColor = 0xFF8B5CF6.toInt()
    private val grey = 0xFF94A3B8.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(40), dp(14), dp(24))
        }
        sv.addView(content)
        sv.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setContentView(sv)
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 1)
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "sample", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SampleWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    override fun onResume() {
        super.onResume()
        if (hasUsage()) Store.sample(this)
        render()
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, stroke: Int? = null, radius: Int = 18) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(2), stroke)
    }

    private fun tint(c: Int) = Color.argb(45, Color.red(c), Color.green(c), Color.blue(c))

    private fun box(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = shape(tint(color), color)
    }

    private fun tv(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f, m: Int = 5) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(m), dp(m), dp(m), dp(m)) }

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun hasUsage(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    private fun current(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return when {
            caps == null -> "لا يوجد اتصال"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "بيانات الجوال"
            else -> "شبكة أخرى"
        }
    }

    private fun days(): List<Long> {
        val r = ArrayList<Long>()
        val c = Calendar.getInstance()
        c.timeInMillis = from
        while (c.timeInMillis <= to && r.size < 93) {
            r.add(c.timeInMillis); c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return r
    }

    private fun end(d: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = d; c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun setLast(n: Int) {
        val t = Store.dayStart(System.currentTimeMillis())
        to = t
        val c = Calendar.getInstance()
        c.timeInMillis = t; c.add(Calendar.DAY_OF_YEAR, -n)
        from = c.timeInMillis
        render()
    }

    private fun pick(isFrom: Boolean) {
        val c = Calendar.getInstance()
        c.timeInMillis = if (isFrom) from else to
        DatePickerDialog(this, { _, y, m, d ->
            val x = Calendar.getInstance()
            x.clear(); x.set(y, m, d)
            val t = x.timeInMillis
            if (isFrom) { from = t; if (to < from) to = t } else { to = t; if (from > to) from = t }
            render()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun chip(label: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE)
        background = shape(color, null, 14)
        setOnClickListener { action() }
    }

    private fun render() {
        content.removeAllViews()
        content.addView(tv("عدّاد الجيجا", 28f, Color.WHITE, true))
        content.addView(tv("متصل الآن: ${current()}", 14f, grey))

        if (!hasUsage()) {
            val b = box(mobColor)
            b.addView(tv("مطلوب إذن الوصول إلى بيانات الاستخدام", 17f, Color.WHITE, true))
            b.addView(tv("فعّل الإذن لتطبيق «عدّاد الجيجا» ثم ارجع للتطبيق.", 14f, grey))
            b.addView(chip("منح الإذن", totalColor) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }, lp(MATCH, dp(48)))
            content.addView(b, lp(MATCH, WRAP, 0f, 8))
            return
        }

        val tabs = LinearLayout(this)
        listOf("الشبكات" to 0, "الخطوط" to 1).forEach { (n, i) ->
            tabs.addView(
                chip(n, if (tab == i) 0xFF6366F1.toInt() else 0xFF1E293B.toInt()) { tab = i; render() },
                lp(0, dp(48), 1f, 4)
            )
        }
        content.addView(tabs, lp(MATCH, WRAP, 0f, 2))

        val quick = LinearLayout(this)
        quick.addView(chip("اليوم", 0xFF334155.toInt()) { setLast(0) }, lp(0, dp(44), 1f, 3))
        quick.addView(chip("7 أيام", 0xFF334155.toInt()) { setLast(6) }, lp(0, dp(44), 1f, 3))
        quick.addView(chip("30 يوم", 0xFF334155.toInt()) { setLast(29) }, lp(0, dp(44), 1f, 3))
        content.addView(quick, lp(MATCH, WRAP, 0f, 2))

        val dates = LinearLayout(this)
        dates.addView(chip("من: ${Store.key(from)}", 0xFF1E293B.toInt()) { pick(true) }, lp(0, dp(44), 1f, 3))
        dates.addView(chip("إلى: ${Store.key(to)}", 0xFF1E293B.toInt()) { pick(false) }, lp(0, dp(44), 1f, 3))
        content.addView(dates, lp(MATCH, WRAP, 0f, 2))

        if (tab == 0) networksTab() else simsTab()
    }

    private fun networksTab() {
        val ds = days()
        val w = ds.map { Store.bytes(this, ConnectivityManager.TYPE_WIFI, it, end(it)) }
        val m = ds.map { Store.bytes(this, ConnectivityManager.TYPE_MOBILE, it, end(it)) }
        val tw = w.sum(); val tm = m.sum(); val all = tw + tm

        val total = box(totalColor)
        total.addView(tv("إجمالي الاستهلاك (${ds.size} يوم)", 14f, grey))
        total.addView(tv(fmt(all), 36f, Color.WHITE, true))
        total.addView(tv("المتوسط اليومي: ${fmt(all / ds.size)}", 14f, grey))
        content.addView(total, lp(MATCH, WRAP, 0f, 5))

        val row = LinearLayout(this)
        fun square(name: String, v: Long, c: Int): LinearLayout {
            val b = box(c)
            b.gravity = Gravity.CENTER
            b.addView(tv(name, 15f, grey))
            b.addView(tv(fmt(v), 24f, c, true))
            val pct = if (all > 0) v * 100 / all else 0
            b.addView(tv("$pct%", 14f, grey))
            return b
        }
        row.addView(square("Wi-Fi", tw, wifiColor), lp(0, dp(150), 1f))
        row.addView(square("بيانات الجوال", tm, mobColor), lp(0, dp(150), 1f))
        content.addView(row, lp(MATCH, WRAP, 0f, 0))

        content.addView(tv("الاستهلاك اليومي", 16f, Color.WHITE, true), lp(MATCH, WRAP, 0f, 8))
        for (i in ds.indices.reversed()) {
            val r = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = shape(0xFF1E293B.toInt())
            }
            r.addView(tv(Store.key(ds[i]), 14f, Color.WHITE), lp(0, WRAP, 1.3f, 0))
            r.addView(tv("Wi-Fi " + fmt(w[i]), 13f, wifiColor, true), lp(0, WRAP, 1f, 0))
            r.addView(tv("جوال " + fmt(m[i]), 13f, mobColor, true), lp(0, WRAP, 1f, 0))
            content.addView(r, lp(MATCH, WRAP, 0f, 4))
        }
    }

    private fun simsTab() {
        val ds = days()
        val tm = ds.sumOf { Store.bytes(this, ConnectivityManager.TYPE_MOBILE, it, end(it)) }
        val ids = Store.sims(this)

        val total = box(mobColor)
        total.addView(tv("إجمالي بيانات الجوال (كل الخطوط)", 14f, grey))
        total.addView(tv(fmt(tm), 36f, Color.WHITE, true))
        content.addView(total, lp(MATCH, WRAP, 0f, 5))

        if (ids.isEmpty()) {
            val n = box(0xFF64748B.toInt())
            n.addView(tv("لا توجد بيانات خطوط بعد. يبدأ التتبع من أول تشغيل، ويحتاج استخدام بيانات الجوال مع إذن الهاتف.", 14f, grey))
            content.addView(n, lp(MATCH, WRAP, 0f, 5))
            return
        }

        val sums = ids.map { id -> ds.sumOf { Store.simBytes(this, id, it) } }
        val tracked = sums.sum()

        var i = 0
        while (i < ids.size) {
            val row = LinearLayout(this)
            for (j in i until minOf(i + 2, ids.size)) {
                val c = simColors[j % simColors.size]
                val b = box(c)
                b.gravity = Gravity.CENTER
                b.addView(tv(Store.simName(this, ids[j]), 15f, grey))
                b.addView(tv(fmt(sums[j]), 24f, c, true))
                val pct = if (tracked > 0) sums[j] * 100 / tracked else 0
                b.addView(tv("$pct%", 14f, grey))
                row.addView(b, lp(0, dp(150), 1f))
            }
            if (i + 1 >= ids.size) row.addView(View(this), lp(0, dp(150), 1f))
            content.addView(row, lp(MATCH, WRAP, 0f, 0))
            i += 2
        }

        content.addView(tv("الاستهلاك اليومي لكل خط", 16f, Color.WHITE, true), lp(MATCH, WRAP, 0f, 8))
        for (d in ds.reversed()) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = shape(0xFF1E293B.toInt())
            }
            r.addView(tv(Store.key(d), 14f, Color.WHITE, true))
            ids.forEachIndexed { k, id ->
                r.addView(
                    tv("${Store.simName(this, id)}: ${fmt(Store.simBytes(this, id, d))}", 13f,
                        simColors[k % simColors.size], true)
                )
            }
            content.addView(r, lp(MATCH, WRAP, 0f, 4))
        }

        content.addView(
            tv("يُنسب الاستهلاك للخط المحدد للبيانات وقت القياس (كل ~15 دقيقة).", 12f, grey),
            lp(MATCH, WRAP, 0f, 8)
        )
    }
}
