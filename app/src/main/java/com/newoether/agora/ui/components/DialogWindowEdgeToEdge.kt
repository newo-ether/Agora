package com.newoether.agora.ui.components

import android.graphics.Color
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

internal val BottomSheetMaxWidth = 640.dp

/** Top-corner shape shared by every bottom sheet in the app (Material's 28dp extra-large corner). */
internal val BOTTOM_SHEET_SHAPE = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * Restores edge-to-edge / transparent navigation bar inside a Compose dialog window
 * ([ModalBottomSheet], [androidx.compose.ui.window.Dialog], popups).
 *
 * Such composables render in their **own** Window, which does not inherit the Activity window's
 * edge-to-edge config. On API 29+ the new window defaults `isNavigationBarContrastEnforced = true`,
 * so the system paints a translucent scrim behind the nav bar — making it look opaque even though
 * the Activity set it transparent. Call this as the first line inside the dialog/sheet content.
 */
@Composable
fun DialogWindowEdgeToEdge() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}

/**
 * Removes Android's independently applied Dialog dim layer. The owning Compose surface must draw
 * and animate its own backdrop/scrim so window creation cannot introduce a one-frame black flash.
 */
@Composable
fun DialogWindowNoSystemDim() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        window.setDimAmount(0f)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }
}

/** Lets Compose own the complete enter/exit motion instead of combining it with a platform
 * window animation whose direction can vary as adjacent dialog windows are removed. */
@Composable
fun DialogWindowNoSystemAnimation() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        window.setWindowAnimations(0)
    }
}

/** Shared window policy for Compose dialogs and activity-result error dialogs. */
fun android.view.Window.applyEinkPresentation() {
    setDimAmount(0f)
    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    setWindowAnimations(0)
}

/** E-ink overrides belong to this dialog and are reversible without recreating its content. */
@Composable
fun DialogWindowEinkPresentation(enabled: Boolean) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    androidx.compose.runtime.DisposableEffect(window, enabled) {
        val dim = window.attributes.dimAmount
        val hadDimFlag = window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
        val animation = window.attributes.windowAnimations
        if (enabled) {
            window.applyEinkPresentation()
        }
        onDispose {
            if (enabled) {
                window.setDimAmount(dim)
                if (hadDimFlag) window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setWindowAnimations(animation)
            }
        }
    }
}
