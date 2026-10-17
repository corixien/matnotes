package com.matnotes.app

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Invisible text-input sink: receives IME and hardware key input and forwards it to LibreOffice.
 * Suggestions are disabled so keyboards commit text directly instead of composing.
 */
class KeyInputView(context: Context) : View(context) {
    var target: DocumentViewState? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION
        return Connection()
    }

    private inner class Connection : BaseInputConnection(this, false) {
        private var composing = ""

        private fun replaceComposing(text: CharSequence) {
            val t = target ?: return
            val new = text.toString()
            var common = 0
            while (common < composing.length && common < new.length && composing[common] == new[common]) common++
            repeat(composing.length - common) { t.pressKey(Keys.BACKSPACE) }
            if (new.length > common) t.typeText(new.substring(common))
            composing = new
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text)
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text)
            composing = ""
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val t = target ?: return true
            repeat(beforeLength) { t.pressKey(Keys.BACKSPACE) }
            repeat(afterLength) { t.pressKey(Keys.DELETE) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) handleKey(event)
            return true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)

    private fun handleKey(event: KeyEvent): Boolean {
        val t = target ?: return false
        val awt = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> Keys.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> Keys.DELETE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Keys.RETURN
            KeyEvent.KEYCODE_DPAD_LEFT -> Keys.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Keys.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Keys.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Keys.DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> Keys.HOME
            KeyEvent.KEYCODE_MOVE_END -> Keys.END
            KeyEvent.KEYCODE_PAGE_UP -> Keys.PAGEUP
            KeyEvent.KEYCODE_PAGE_DOWN -> Keys.PAGEDOWN
            KeyEvent.KEYCODE_TAB -> Keys.TAB
            else -> 0
        }
        if (awt != 0) { t.pressKey(awt); return true }
        val ch = event.unicodeChar
        if (ch > 0 && !event.isCtrlPressed) { t.typeText(String(Character.toChars(ch))); return true }
        return false
    }

    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }
}
