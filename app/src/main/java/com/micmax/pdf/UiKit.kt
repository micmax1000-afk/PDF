package com.micmax.pdf

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Small helpers: the whole screen is built in code, with the colours of the theme. */
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.col(id: Int) = ContextCompat.getColor(this, id)

fun Context.roundBg(color: Int, radiusDp: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
    cornerRadius = dp(radiusDp).toFloat(); setColor(color); if (stroke != null) setStroke(dp(1), stroke)
}

fun Context.sectionLabel(parent: LinearLayout, text: String) {
    parent.addView(TextView(this).apply {
        this.text = text; textSize = 12f; letterSpacing = 0.08f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(col(R.color.muted)); setPadding(dp(6), dp(16), dp(6), dp(6))
    })
}

fun Context.hintLabel(parent: LinearLayout, text: String) {
    parent.addView(TextView(this).apply { this.text = text; textSize = 13.5f; setTextColor(col(R.color.muted)); setPadding(dp(6), dp(4), dp(6), dp(8)) })
}

/** A white card row: round icon, title, small line, tap. */
fun Context.listRow(parent: LinearLayout, icon: String, title: String, sub: String?, onLong: (() -> Unit)? = null, onClick: () -> Unit): View {
    val r = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = roundBg(col(R.color.card), 16, col(R.color.stroke)); setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        isClickable = true; setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
    }
    r.addView(TextView(this).apply {
        text = icon; textSize = 21f; gravity = Gravity.CENTER; background = roundBg(col(R.color.icbg), 12)
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) }
    })
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
    col.addView(TextView(this).apply { text = title; textSize = 15.5f; setTextColor(col(R.color.text)); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
    if (!sub.isNullOrBlank()) col.addView(TextView(this).apply { text = sub; textSize = 12.5f; setTextColor(col(R.color.muted)) })
    r.addView(col)
    parent.addView(r)
    return r
}

/** The big square buttons of the home page. */
fun Context.bigButton(emoji: String, label: String, primary: Boolean, onClick: () -> Unit): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
    background = roundBg(if (primary) col(R.color.accent) else col(R.color.card), 18, if (primary) null else col(R.color.stroke))
    setPadding(dp(8), dp(16), dp(8), dp(16)); isClickable = true; setOnClickListener { onClick() }
    addView(TextView(this@bigButton).apply { text = emoji; textSize = 28f; gravity = Gravity.CENTER })
    addView(TextView(this@bigButton).apply {
        text = label; textSize = 14.5f; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(if (primary) android.graphics.Color.WHITE else col(R.color.text))
    })
}

/** A row of rounded chips that scrolls sideways. */
fun Context.chipBar(chips: List<Pair<String, () -> Unit>>): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
    chips.forEach { (t, f) ->
        row.addView(TextView(this).apply {
            text = t; textSize = 13.5f; setTextColor(col(R.color.text)); setPadding(dp(13), dp(9), dp(13), dp(9))
            background = roundBg(col(R.color.card), 18, col(R.color.stroke))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
            isClickable = true; setOnClickListener { f() }
        })
    }
    return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
}

fun Context.primaryButton(text: String, onClick: () -> Unit) = TextView(this).apply {
    this.text = text; textSize = 16f; gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
    background = roundBg(col(R.color.accent), 16); setPadding(dp(12), dp(14), dp(12), dp(14))
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    isClickable = true; setOnClickListener { onClick() }
}
