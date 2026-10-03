package com.whitedevil

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Shared visual polish for the WhiteDevil native shell (Forge Hub dark-glass aesthetic). */
object UiPolish {

    fun dp(ctx: Context, v: Int): Float = v * ctx.resources.displayMetrics.density

    fun dpInt(ctx: Context, v: Int): Int = dp(ctx, v).toInt()

    fun modelLabel(modelId: String): String = when (modelId) {
        "zai-org-glm-5-2" -> "GLM 5.2"
        "zai-org-glm-5" -> "GLM 5"
        "venice-uncensored" -> "Uncensored"
        "venice-uncensored-1-2" -> "Uncensored 1.2 (chat only)"
        "venice-uncensored-role-play" -> "Role Play Uncensored (chat only)"
        "gemma-4-uncensored" -> "Gemma 4 Uncensored"
        "qwen-3-6-plus" -> "Qwen 3.6 Plus Uncensored (slow)"
        "olafangensan-glm-4.7-flash-heretic" -> "GLM 4.7 Flash Heretic (slow)"
        "abliteration-abliterated-model-large-v2" -> "Abliterated Large V2"
        "e2ee-gemma-4-26b-a4b-uncensored-p" -> "Gemma 4 26B Uncensored (E2EE, chat only)"
        "kimi-k2-6" -> "Kimi K2.6"
        "claude-opus-4-8" -> "Opus 4.8"
        else -> modelId.substringAfterLast('-').replaceFirstChar { it.uppercase() }
    }

    fun glass(fill: Int, radiusDp: Float, border: Int? = null, borderWidthDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            if (radiusDp > 0f) cornerRadius = radiusDp
            if (border != null) setStroke(borderWidthDp, border)
        }

    /** Soft vignette behind tab content. */
    fun screenGradient(ctx: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            Color.parseColor("#FF141210"),
            Color.parseColor("#FF0B0B0C"),
            Color.parseColor("#FF080809"),
        ),
    )

    fun bubbleDrawable(role: Int, radiusPx: Float): GradientDrawable {
        val r = radiusPx
        val small = radiusPx * 0.35f
        val (fill, border) = when (role) {
            MainActivity.ROLE_USER -> Color.parseColor("#3D4A3828") to Color.parseColor("#55CDB88F")
            MainActivity.ROLE_VENICE -> Color.parseColor("#331A1A1E") to Color.parseColor("#33FFFFFF")
            MainActivity.ROLE_INFO -> Color.parseColor("#2A1E1810") to Color.parseColor("#44CDB88F")
            MainActivity.ROLE_TOOL_CALL -> Color.parseColor("#331F2E3D") to Color.parseColor("#665C8BB5")
            MainActivity.ROLE_TOOL_OUTPUT -> Color.parseColor("#291D1D24") to Color.parseColor("#22FFFFFF")
            else -> Color.parseColor("#44331111") to Color.parseColor("#66CC5555")
        }
        val radii = when (role) {
            MainActivity.ROLE_USER -> floatArrayOf(r, r, r, r, small, small, r, r)
            MainActivity.ROLE_VENICE, MainActivity.ROLE_INFO ->
                floatArrayOf(r, r, r, r, r, r, small, small)
            else -> floatArrayOf(r, r, r, r, r, r, r, r)
        }
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadii = radii
            setStroke(1, border)
        }
    }

    fun avatar(ctx: Context, letter: String, bg: Int, fg: Int): TextView = TextView(ctx).apply {
        text = letter
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(fg)
        background = glass(bg, dp(ctx, 14))
    }

    fun iconCircle(
        ctx: Context,
        iconRes: Int,
        tint: Int,
        contentDescription: String,
        onClick: () -> Unit,
    ): FrameLayout =
        FrameLayout(ctx).apply {
            background = glass(Color.parseColor("#331A1A1E"), dp(ctx, 14), Color.parseColor("#22FFFFFF"))
            isClickable = true
            this.contentDescription = contentDescription
            minimumWidth = dpInt(ctx, 48)
            minimumHeight = dpInt(ctx, 48)
            setOnClickListener { onClick() }
            addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(tint)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dpInt(ctx, 22), dpInt(ctx, 22), Gravity.CENTER))
        }

    fun sectionCard(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(Color.parseColor("#331A1A1E"), dp(ctx, 16), Color.parseColor("#18FFFFFF"))
        setPadding(dpInt(ctx, 16), dpInt(ctx, 14), dpInt(ctx, 16), dpInt(ctx, 14))
    }
}
