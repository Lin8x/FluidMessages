package org.fluidmessages

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Private keyboard (PrivateKeyboard): which fields ask the keyboard not to learn what is typed. */
class PrivateKeyboardTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val message = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    private val search = text or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
    private val password = text or InputType.TYPE_TEXT_VARIATION_PASSWORD
    private val shownPassword = text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    private val webPassword = text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    private val passcode = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    private val number = InputType.TYPE_CLASS_NUMBER
    private val phone = InputType.TYPE_CLASS_PHONE
    private val noLearning = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

    @Test fun settingOnMakesEveryFieldPrivate() {
        for (type in listOf(message, search, number, phone, password, passcode, text or InputType.TYPE_TEXT_VARIATION_URI)) {
            assertTrue(PrivateKeyboard.shouldBePrivate(true, type))
        }
    }

    @Test fun settingOffLeavesOrdinaryFieldsAlone() {
        for (type in listOf(message, search, number, phone, text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) {
            assertFalse(PrivateKeyboard.shouldBePrivate(false, type))
        }
    }

    @Test fun passwordsAndPasscodesAreAlwaysPrivate() {
        for (type in listOf(password, shownPassword, webPassword, passcode)) {
            assertTrue(PrivateKeyboard.isSecret(type))
            assertTrue(PrivateKeyboard.shouldBePrivate(false, type))
        }
    }

    @Test fun variationsOnlyCountInTheirOwnClass() {
        // TYPE_NUMBER_VARIATION_PASSWORD and TYPE_TEXT_VARIATION_URI share a value; a web address isn't a password.
        assertFalse(PrivateKeyboard.isSecret(text or InputType.TYPE_TEXT_VARIATION_URI))
        assertFalse(PrivateKeyboard.isSecret(phone))
    }

    @Test fun flagIsAddedWithoutDroppingOtherOptions() {
        val send = EditorInfo.IME_ACTION_SEND
        assertEquals(send or noLearning, PrivateKeyboard.imeOptions(send, message, true))
        assertEquals(send, PrivateKeyboard.imeOptions(send, message, false))
        assertEquals(send or noLearning, PrivateKeyboard.imeOptions(send, password, false))
        assertEquals(send or noLearning, PrivateKeyboard.imeOptions(send or noLearning, message, true))
    }
}
