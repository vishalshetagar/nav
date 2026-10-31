package com.example.nav.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** A trusted accessibility overlay: highlights do not consume or block touches. */
class HighlightOverlay(context: Context, private val owner: FloatingBubbleService, private val windowManager: WindowManager) {
    private val context = context
    private var view: ComposeView? = null
    private var target by mutableStateOf<Rect?>(null)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    fun showHighlight(bounds: Rect) {
        target = Rect(bounds)
        if (view != null) return
        view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val transition = rememberInfiniteTransition(label = "target")
                val width by transition.animateFloat(3f, 7f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "outline")
                Canvas(Modifier.fillMaxSize()) {
                    val rect = target ?: return@Canvas
                    val location = IntArray(2)
                    view?.getLocationOnScreen(location)
                    drawRoundRect(Color(0xFF00E5FF),
                        Offset(rect.left.toFloat() - location[0], rect.top.toFloat() - location[1]),
                        Size(rect.width().toFloat(), rect.height().toFloat()), CornerRadius(10.dp.toPx()),
                        style = Stroke(width.dp.toPx()))
                }
            }
        }
        windowManager.addView(view, params)
    }

    fun clear() {
        target = null
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }
}
