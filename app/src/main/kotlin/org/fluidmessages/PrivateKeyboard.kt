package org.fluidmessages

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Settings → Privacy and security → Private keyboard: asks the keyboard not to learn or remember what is typed
 * (IME_FLAG_NO_PERSONALIZED_LEARNING). Password and passcode fields always ask, whatever the setting. Keyboards
 * may still ignore the request; it is a hint Android passes along.
 */
object PrivateKeyboard {
    const val PREF = "privateKeyboard"

    @JvmStatic
    fun enabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF, true)

    /** Passwords, visible passwords, web passwords, and number passcodes. */
    @JvmStatic
    fun isSecret(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** The rule: every field when the setting is on; secret fields always. */
    @JvmStatic
    fun shouldBePrivate(settingOn: Boolean, inputType: Int): Boolean = settingOn || isSecret(inputType)

    /** imeOptions with the no-learning flag added when the field should be private, unchanged otherwise. */
    @JvmStatic
    fun imeOptions(imeOptions: Int, inputType: Int, settingOn: Boolean): Int =
        if (shouldBePrivate(settingOn, inputType)) imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else imeOptions

    /**
     * Every Compose text field inside: AppTheme wraps each page with this, so the composer, search, account and
     * password forms, and passcodes all get the flag without each call site asking.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    internal fun Provide(content: @Composable () -> Unit) {
        val context = LocalContext.current
        val on = remember(context) { enabled(context.getSharedPreferences("fluid", Context.MODE_PRIVATE)) }
        InterceptPlatformTextInput(interceptor = { request, nextHandler ->
            val private = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    outAttributes.imeOptions = imeOptions(outAttributes.imeOptions, outAttributes.inputType, on)
                    return connection
                }
            }
            nextHandler.startInputMethod(private)
        }, content = content)
    }
}
