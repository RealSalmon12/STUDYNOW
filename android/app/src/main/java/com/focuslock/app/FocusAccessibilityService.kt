package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * 잠금 시간 동안 허용하지 않은 앱이 앞으로 오면 잠금 화면으로 돌려보낸다.
 * 접근성 서비스는 재부팅 후에도 시스템이 자동으로 다시 켜 주기 때문에
 * 폰을 껐다 켜도 남은 시간부터 잠금이 이어진다.
 */
class FocusAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastTick = 0L
    private var lastBounce = 0L

    /** 잠금 중에도 항상 허용 (전화·긴급통화, 키보드, 시스템 화면) */
    private val alwaysAllowed by lazy {
        val set = mutableSetOf(
            packageName,
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.samsung.android.incallui",
            "com.android.emergency",
            "com.samsung.android.emergency",
        )
        (getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage?.let { set += it }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.enabledInputMethodList?.forEach { set += it.packageName }
        set
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        lastTick = SystemClock.elapsedRealtime()
        handler.post(ticker)
        if (LockState.isActive(this)) showLock()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val delta = now - lastTick // 화면 꺼진 시간도 포함 (재부팅 시간은 제외)
        lastTick = now
        val remaining = LockState.remainingMs(this)
        if (remaining <= 0) return
        val next = remaining - delta
        LockState.setRemaining(this, next)
        if (next <= 0) {
            Toast.makeText(this, "수고했어요! 잠금이 풀렸어요.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!LockState.isActive(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in alwaysAllowed || pkg in LockState.allowed(this)) return
        // 홈 화면, 최근 앱, 설정, 다른 모든 앱 -> 잠금 화면으로
        showLock()
    }

    private fun showLock() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBounce < 300) return
        lastBounce = now
        startActivity(
            Intent(this, LockActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        )
    }

    companion object {
        @Volatile
        var running = false

        fun isEnabled(c: Context): Boolean {
            val enabled = Settings.Secure.getString(
                c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = "${c.packageName}/${FocusAccessibilityService::class.java.name}"
            val meShort = "${c.packageName}/.FocusAccessibilityService"
            return enabled.split(':').any { it.equals(me, true) || it.equals(meShort, true) }
        }
    }
}
