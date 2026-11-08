package com.focuslock.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

object Ui {
    val BG = Color.parseColor("#0F1115")
    val CARD = Color.parseColor("#1B1F27")
    val FG = Color.parseColor("#E8E8EA")
    val DIM = Color.parseColor("#8B8F99")
    val ACCENT = Color.parseColor("#7CC4A4")

    fun dp(c: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    fun text(c: Context, s: String, sp: Float, color: Int = FG, bold: Boolean = false) =
        TextView(c).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    fun button(c: Context, s: String, primary: Boolean = false, onClick: () -> Unit) =
        Button(c).apply {
            text = s
            isAllCaps = false
            setTextColor(if (primary) BG else FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 17f else 14f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(c, 12).toFloat()
                setColor(if (primary) ACCENT else CARD)
            }
            setOnClickListener { onClick() }
        }
}
