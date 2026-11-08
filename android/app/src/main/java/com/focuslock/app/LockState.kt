package com.focuslock.app

import android.content.Context

/** 잠금 상태 저장. 남은 시간은 잠금 감시가 켜져 있을 때만 줄어든다. */
object LockState {
    private const val PREFS = "focus_lock"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isActive(c: Context) = remainingMs(c) > 0

    fun remainingMs(c: Context) = prefs(c).getLong("remaining", 0L)

    fun totalMs(c: Context) = prefs(c).getLong("total", 0L)

    fun allowed(c: Context): Set<String> = prefs(c).getStringSet("allowed", emptySet()) ?: emptySet()

    fun lastMinutes(c: Context) = prefs(c).getInt("last_minutes", 50)

    fun start(c: Context, minutes: Int, allowed: Set<String>) {
        val ms = minutes * 60_000L
        prefs(c).edit()
            .putLong("remaining", ms)
            .putLong("total", ms)
            .putStringSet("allowed", allowed)
            .putInt("last_minutes", minutes)
            .commit()
    }

    fun setRemaining(c: Context, ms: Long) {
        prefs(c).edit().putLong("remaining", ms.coerceAtLeast(0L)).apply()
    }

    fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}
