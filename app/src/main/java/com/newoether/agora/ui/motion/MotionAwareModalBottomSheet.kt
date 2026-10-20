package com.newoether.agora.ui.motion

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet as MaterialModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.newoether.agora.ui.components.BOTTOM_SHEET_SHAPE
import com.newoether.agora.ui.components.BottomSheetMaxWidth
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.components.Surface

/**
 * Uses Material's draggable sheet normally and an immediate, stationary sheet when motion is
 * reduced. The reduced variant keeps the same modal state and dismissal boundaries without the
 * large bottom-to-top entrance/exit travel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionAwareModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (LocalAgoraMotionPolicy.current.allowSpatialTransitions) {
        MaterialModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier.widthIn(max = BottomSheetMaxWidth),
            sheetState = sheetState,
            shape = BOTTOM_SHEET_SHAPE,
            containerColor = containerColor,
            content = content,
        )
        return
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        DialogWindowEdgeToEdge()
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setWindowAnimations(0)
            window?.setDimAmount(0f)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (LocalAgoraMotionPolicy.current.eInkMode) Color.White else BottomSheetDefaults.ScrimColor),
        ) {
            val scrimInteractionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null,
                        onClick = onDismissRequest,
                    ),
            )
            Surface(
                modifier = modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .widthIn(max = BottomSheetMaxWidth)
                    // Register the stationary sheet itself as the top hit target. Children still
                    // receive their events, while taps on blank sheet space cannot fall through
                    // to the dismissing scrim behind it.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent()
                            }
                        }
                    },
                shape = BOTTOM_SHEET_SHAPE,
                color = containerColor,
                contentColor = contentColorFor(containerColor),
            ) {
                Column {
                    content()
                }
            }
        }
    }
}
