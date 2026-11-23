package org.fluidmessages

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What sits behind the call screen: the chat's own wallpaper, dimmed so the call controls stay readable, with the
 * call's protection at the top, the same small lock as in the chat.
 */
object CallBackdrop {
    @JvmStatic
    fun view(context: Context, address: String, protocol: String, encryption: Encryption): ComposeView {
        val prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE)
        return ComposeView(context).apply {
            setContent {
                val appearance = remember { resolveChatAppearance(Appearance.read(prefs), ChatProfile.read(prefs, address)) }
                WallpaperSurface(appearance.wallpaper, Color(appearance.canvas), Modifier.fillMaxSize()) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .55f)))
                }
            }
        }
    }
}
