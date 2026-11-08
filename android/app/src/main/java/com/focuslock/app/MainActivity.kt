package com.focuslock.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.res.ColorStateList

/** 설정 화면: 시간, 허용 앱 고르기, 접근성 권한 안내, 잠금 시작 */
class MainActivity : Activity() {

    private lateinit var minutesInput: EditText
    private lateinit var permText: TextView
    private val checks = mutableMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (LockState.isActive(this)) {
            startActivity(Intent(this, LockActivity::class.java))
            return
        }
        val on = FocusAccessibilityService.isEnabled(this)
        permText.text = if (on) "✓ 접근성 권한 켜짐" else "접근성 권한을 켜야 잠글 수 있어요 (눌러서 설정 열기)"
        permText.setTextColor(if (on) Ui.ACCENT else Ui.FG)
    }

    private fun buildUi() {
        val pad = Ui.dp(this, 22)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(this@MainActivity, 40), pad, pad)
        }
        col.addView(Ui.text(this, "공부 집중 잠금", 26f, bold = true))
        col.addView(Ui.text(this, "시작하면 정한 시간이 끝날 때까지 풀 수 없어요.", 13f, Ui.DIM))

        permText = Ui.text(this, "", 14f).apply {
            setPadding(Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 12))
            setBackgroundColor(Ui.CARD)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        col.addView(permText, lp(top = 20))

        col.addView(Ui.text(this, "잠금 시간 (분)", 15f, bold = true), lp(top = 24))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        minutesInput = EditText(this).apply {
            setText(LockState.lastMinutes(this@MainActivity).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Ui.FG)
            textSize = 20f
            gravity = Gravity.CENTER
            backgroundTintList = ColorStateList.valueOf(Ui.ACCENT)
        }
        row.addView(minutesInput, LinearLayout.LayoutParams(Ui.dp(this, 80), LinearLayout.LayoutParams.WRAP_CONTENT))
        for (m in listOf(25, 50, 90, 120)) {
            row.addView(Ui.button(this, "${m}분") { minutesInput.setText(m.toString()) },
                LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f).apply { leftMargin = Ui.dp(this@MainActivity, 6) })
        }
        col.addView(row, lp(top = 6))

        col.addView(Ui.text(this, "잠금 중에도 쓸 앱", 15f, bold = true), lp(top = 24))
        col.addView(Ui.text(this, "전화·긴급통화와 키보드는 항상 쓸 수 있어요.", 12f, Ui.DIM))
        val saved = LockState.allowed(this)
        for ((pkg, label, icon) in launchableApps()) {
            val item = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            item.addView(ImageView(this).apply { setImageDrawable(icon) },
                LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)))
            val cb = CheckBox(this).apply {
                text = label
                setTextColor(Ui.FG)
                buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
                isChecked = pkg in saved
            }
            checks[pkg] = cb
            item.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Ui.dp(this@MainActivity, 10)
            })
            item.setOnClickListener { cb.toggle() }
            col.addView(item, lp(top = 6))
        }

        val scroll = ScrollView(this).apply { addView(col) }
        val start = Ui.button(this, "잠금 시작", primary = true) { confirmStart() }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(start, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 56)).apply {
                setMargins(pad, Ui.dp(this@MainActivity, 8), pad, pad)
            })
        }
        setContentView(outer)
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Ui.dp(this@MainActivity, top) }

    private fun launchableApps(): List<Triple<String, String, android.graphics.drawable.Drawable>> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .map { (pkg, ri) -> Triple(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)) }
            .sortedBy { it.second }
    }

    private fun confirmStart() {
        val minutes = minutesInput.text.toString().toIntOrNull()
        if (minutes == null || minutes !in 1..1440) {
            AlertDialog.Builder(this).setMessage("시간은 1분에서 1440분 사이로 입력해 주세요.")
                .setPositiveButton("확인", null).show()
            return
        }
        if (!FocusAccessibilityService.isEnabled(this)) {
            AlertDialog.Builder(this)
                .setTitle("접근성 권한이 필요해요")
                .setMessage("설정 > 접근성 > 설치된 앱 > 공부 집중 잠금 을 켜 주세요.\n\n켜지지 않고 회색이면: 설정 > 애플리케이션 > 공부 집중 잠금 > 오른쪽 위 ⋮ > '제한된 설정 허용' 을 먼저 눌러 주세요.")
                .setPositiveButton("설정 열기") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("취소", null).show()
            return
        }
        val allowed = checks.filterValues { it.isChecked }.keys
        AlertDialog.Builder(this)
            .setTitle("정말 시작할까요?")
            .setMessage("${minutes}분 동안 폰이 잠겨요.\n허용한 앱 ${allowed.size}개와 전화만 쓸 수 있고, 중간에 풀 방법은 없어요.")
            .setPositiveButton("시작") { _, _ ->
                LockState.start(this, minutes, allowed)
                startActivity(Intent(this, LockActivity::class.java))
            }
            .setNegativeButton("취소", null).show()
    }
}
