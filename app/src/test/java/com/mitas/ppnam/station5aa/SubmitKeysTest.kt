package com.mitas.ppnam.station5aa

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One rule for "the operator pressed submit": the IME action button, or a hardware Enter —
 * which the C72 keypad and a scanner-wedge suffix deliver as actionId IME_NULL plus a
 * KEYCODE_ENTER KeyEvent (UI audit group (b)). Only compile-time constants from android.jar
 * are used here, so this runs on the plain JVM.
 */
class SubmitKeysTest {

    @Test
    fun `IME Done submits`() {
        assertTrue(SubmitKeys.isSubmit(EditorInfo.IME_ACTION_DONE, SubmitKeys.NO_KEY, SubmitKeys.NO_KEY))
    }

    @Test
    fun `IME Go, Send and Search submit`() {
        assertTrue(SubmitKeys.isSubmit(EditorInfo.IME_ACTION_GO, SubmitKeys.NO_KEY, SubmitKeys.NO_KEY))
        assertTrue(SubmitKeys.isSubmit(EditorInfo.IME_ACTION_SEND, SubmitKeys.NO_KEY, SubmitKeys.NO_KEY))
        assertTrue(SubmitKeys.isSubmit(EditorInfo.IME_ACTION_SEARCH, SubmitKeys.NO_KEY, SubmitKeys.NO_KEY))
    }

    @Test
    fun `IME Next does not submit`() {
        assertFalse(SubmitKeys.isSubmit(EditorInfo.IME_ACTION_NEXT, SubmitKeys.NO_KEY, SubmitKeys.NO_KEY))
    }

    @Test
    fun `hardware Enter key-down submits`() {
        assertTrue(SubmitKeys.isSubmit(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN))
        assertTrue(SubmitKeys.isSubmit(EditorInfo.IME_NULL, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN))
    }

    @Test
    fun `hardware Enter key-up is swallowed but does not submit a second time`() {
        assertFalse(SubmitKeys.isSubmit(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP))
        assertTrue(SubmitKeys.isEnterKey(KeyEvent.KEYCODE_ENTER))
        assertTrue(SubmitKeys.isEnterKey(KeyEvent.KEYCODE_NUMPAD_ENTER))
    }

    @Test
    fun `other keys neither submit nor get swallowed`() {
        assertFalse(SubmitKeys.isSubmit(EditorInfo.IME_NULL, KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN))
        assertFalse(SubmitKeys.isEnterKey(KeyEvent.KEYCODE_A))
        assertFalse(SubmitKeys.isEnterKey(SubmitKeys.NO_KEY))
    }
}
