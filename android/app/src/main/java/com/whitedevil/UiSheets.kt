package com.whitedevil

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Material bottom sheets for list picks and short forms (attach, model, API key). */
object UiSheets {

    fun showListSheet(
        ctx: Context,
        title: String,
        items: Array<String>,
        onPick: (Int) -> Unit,
    ) {
        val dialog = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiPolish.dpInt(ctx, 20), UiPolish.dpInt(ctx, 8), UiPolish.dpInt(ctx, 20), UiPolish.dpInt(ctx, 24))
        }
        root.addView(TextView(ctx).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MainActivity.STRONG)
            setPadding(0, UiPolish.dpInt(ctx, 8), 0, UiPolish.dpInt(ctx, 12))
        })
        items.forEachIndexed { index, label ->
            root.addView(TextView(ctx).apply {
                text = label
                textSize = 15f
                setTextColor(MainActivity.FG)
                setPadding(UiPolish.dpInt(ctx, 4), UiPolish.dpInt(ctx, 14), UiPolish.dpInt(ctx, 4), UiPolish.dpInt(ctx, 14))
                isClickable = true
                background = UiPolish.glass(
                    Color.parseColor("#28000000"),
                    UiPolish.dp(ctx, 12),
                    MainActivity.LINE,
                )
                setOnClickListener {
                    onPick(index)
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = UiPolish.dpInt(ctx, 8)
            })
        }
        dialog.setContentView(root)
        dialog.show()
    }

    fun showSecretFieldSheet(
        ctx: Context,
        title: String,
        hint: String,
        initial: String,
        onSave: (String) -> Unit,
    ) {
        val dialog = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiPolish.dpInt(ctx, 20), UiPolish.dpInt(ctx, 8), UiPolish.dpInt(ctx, 20), UiPolish.dpInt(ctx, 24))
        }
        root.addView(TextView(ctx).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MainActivity.STRONG)
        })
        val field = EditText(ctx).apply {
            setText(initial)
            this.hint = hint
            setHintTextColor(MainActivity.MUTED)
            setTextColor(MainActivity.STRONG)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = UiPolish.glass(Color.parseColor("#28000000"), UiPolish.dp(ctx, 12), MainActivity.LINE)
            setPadding(UiPolish.dpInt(ctx, 14), UiPolish.dpInt(ctx, 12), UiPolish.dpInt(ctx, 14), UiPolish.dpInt(ctx, 12))
        }
        root.addView(
            field,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = UiPolish.dpInt(ctx, 12) },
        )
        val save = TextView(ctx).apply {
            text = "Save"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF111111"))
            setPadding(UiPolish.dpInt(ctx, 20), UiPolish.dpInt(ctx, 14), UiPolish.dpInt(ctx, 20), UiPolish.dpInt(ctx, 14))
            background = UiPolish.glass(MainActivity.ACCENT, UiPolish.dp(ctx, 20))
            isClickable = true
            setOnClickListener {
                onSave(field.text.toString().trim())
                dialog.dismiss()
            }
        }
        root.addView(
            save,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = UiPolish.dpInt(ctx, 16) },
        )
        dialog.setContentView(root)
        dialog.show()
    }
}
