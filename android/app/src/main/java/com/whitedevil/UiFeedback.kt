package com.whitedevil

import android.view.View
import com.google.android.material.snackbar.Snackbar

object UiFeedback {

    fun snackbar(anchor: View, message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val bar = Snackbar.make(anchor, message, Snackbar.LENGTH_LONG)
        if (action != null && onAction != null) {
            bar.setAction(action) { onAction() }
            bar.setActionTextColor(MainActivity.ACCENT)
        }
        bar.show()
    }
}
