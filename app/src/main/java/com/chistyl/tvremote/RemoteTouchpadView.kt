package com.chistyl.tvremote

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.abs

/**
 * Gesture surface for Android TV Remote v2.
 * The protocol exposes key injection rather than pointer coordinates, so swipes
 * are translated into DPAD key events (not advertised as a true mouse).
 */
class RemoteTouchpadView(
    context: Context,
    private val onKey: (Int) -> Unit
) : TextView(context) {
    private var downX = 0f
    private var downY = 0f
    private val swipeThreshold = 38f * resources.displayMetrics.density

    init {
        text = "在此滑動或點按"
        textSize = 15f
        gravity = android.view.Gravity.CENTER
        setTextColor(0xFFE8EDF7.toInt())
        background = GradientDrawable().apply {
            setColor(0xFF171F2D.toInt())
            cornerRadius = 18f * resources.displayMetrics.density
            setStroke((1f * resources.displayMetrics.density).toInt().coerceAtLeast(1), 0xFF34435A.toInt())
        }
        isClickable = true
        isFocusable = true
        contentDescription = "觸控板，滑動移動焦點，點按確認"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isPressed = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) < swipeThreshold && abs(dy) < swipeThreshold) {
                    onKey(KEYCODE_DPAD_CENTER)
                    performClick()
                } else if (abs(dx) > abs(dy)) {
                    onKey(if (dx > 0) KEYCODE_DPAD_RIGHT else KEYCODE_DPAD_LEFT)
                } else {
                    onKey(if (dy > 0) KEYCODE_DPAD_DOWN else KEYCODE_DPAD_UP)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_DPAD_CENTER = 23
    }
}
