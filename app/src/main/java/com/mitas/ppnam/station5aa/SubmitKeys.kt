package com.mitas.ppnam.station5aa

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView

/**
 * One rule for "the operator pressed submit in this field": the IME's action button
 * (Done / Go / Send / Search) or a hardware Enter. The C72 keypad and a scanner-wedge
 * suffix send the latter, which reaches OnEditorActionListener as actionId IME_NULL plus a
 * KEYCODE_ENTER KeyEvent for both ACTION_DOWN and ACTION_UP — listeners that only check
 * actionId ignore it (UI audit group (b)).
 */
object SubmitKeys {
    /** Sentinel for "no KeyEvent" (IME action buttons arrive with a null event). */
    const val NO_KEY = -1

    /** A held key repeats ACTION_DOWN with repeatCount > 0; only the first press submits. */
    fun isSubmit(actionId: Int, keyCode: Int, keyAction: Int, repeatCount: Int = 0): Boolean {
        val imeAction = actionId == EditorInfo.IME_ACTION_DONE ||
            actionId == EditorInfo.IME_ACTION_GO ||
            actionId == EditorInfo.IME_ACTION_SEND ||
            actionId == EditorInfo.IME_ACTION_SEARCH
        val enterDown = isEnterKey(keyCode) && keyAction == KeyEvent.ACTION_DOWN && repeatCount == 0
        return imeAction || enterDown
    }

    /** Enter in either of its key codes; the ACTION_UP half must be consumed too, or the
     *  field inserts a newline / the next focused view receives a click. */
    fun isEnterKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
}

/** Runs [action] once per submit gesture (IME action or hardware Enter key-down). */
fun TextView.setOnSubmit(action: () -> Unit) {
    setOnEditorActionListener { _, actionId, event ->
        val keyCode = event?.keyCode ?: SubmitKeys.NO_KEY
        val keyAction = event?.action ?: SubmitKeys.NO_KEY
        when {
            SubmitKeys.isSubmit(actionId, keyCode, keyAction, event?.repeatCount ?: 0) -> {
                action()
                true
            }
            SubmitKeys.isEnterKey(keyCode) -> true // swallow the ACTION_UP half
            else -> false
        }
    }
}
