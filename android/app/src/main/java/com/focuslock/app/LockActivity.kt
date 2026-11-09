package com.focuslock.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import android.content.res.ColorStateList

/** 잠금 화면: 남은 시간 + 허용 앱 실행 버튼. 뒤로 가기로 나갈 수 없다. */
class LockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var bar: ProgressBar
    private lateinit var warn: TextView

    private val updater = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY
            ) { /* 무시 */ }
        }

        val pad = Ui.dp(this, 24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.BG)
            setPadding(pad, Ui.dp(this@LockActivity, 96), pad, pad)
        }
        root.addView(Ui.text(this, "집중 시간", 18f, Ui.DIM).apply { gravity = Gravity.CENTER })
        clock = Ui.text(this, "", 72f, Ui.FG, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(clock)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.CARD)
        }
        root.addView(bar, LinearLayout.LayoutParams(Ui.dp(this, 260), Ui.dp(this, 8)).apply {
            topMargin = Ui.dp(this@LockActivity, 8); bottomMargin = Ui.dp(this@LockActivity, 40)
        })

        warn = Ui.text(this, "", 13f, Ui.ACCENT).apply { gravity = Gravity.CENTER }
        root.addView(warn)

        val allowed = LockState.allowed(this).sorted()
        if (allowed.isNotEmpty()) {
            root.addView(Ui.text(this, "쓸 수 있는 앱", 13f, Ui.DIM).apply {
                gravity = Gravity.CENTER; setPadding(0, Ui.dp(this@LockActivity, 8), 0, Ui.dp(this@LockActivity, 12))
            })
            val grid = GridLayout(this).apply { columnCount = 4 }
            for (pkg in allowed) {
                val cell = appCell(pkg) ?: continue
                grid.addView(cell)
            }
            root.addView(grid)
        }

        root.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        root.addView(Ui.button(this, "긴급 전화") {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Ui.BG)
            addView(root)
        })
    }

    private fun appCell(pkg: String): View? {
        val pm = packageManager
        val info = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { return null }
        val size = Ui.dp(this, 52)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(this@LockActivity, 8), Ui.dp(this@LockActivity, 8), Ui.dp(this@LockActivity, 8), Ui.dp(this@LockActivity, 8))
            addView(ImageView(this@LockActivity).apply { setImageDrawable(pm.getApplicationIcon(info)) },
                LinearLayout.LayoutParams(size, size))
            addView(Ui.text(this@LockActivity, pm.getApplicationLabel(info).toString(), 12f).apply {
                gravity = Gravity.CENTER; maxLines = 1; width = Ui.dp(this@LockActivity, 72)
            })
            setOnClickListener {
                pm.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        handler.post(updater)
    }

    override fun onPause() {
        handler.removeCallbacks(updater)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* 잠금 중에는 뒤로 가기 무시 */
        if (!LockState.isActive(this)) super.onBackPressed()
    }

    private fun refresh() {
        val remaining = LockState.remainingMs(this)
        if (remaining <= 0) {
            clock.text = "완료!"
            warn.text = "수고했어요. 잠금이 풀렸어요."
            handler.postDelayed({ finish() }, 2500)
            handler.removeCallbacks(updater)
            return
        }
        clock.text = LockState.fmt(remaining)
        val total = LockState.totalMs(this).coerceAtLeast(1)
        bar.progress = (1000 * (total - remaining) / total).toInt()
        warn.text = if (!FocusAccessibilityService.isEnabled(this))
            "접근성 권한이 꺼져 있어 시간이 멈췄어요. 다시 켜야 시간이 흘러요." else ""
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}
