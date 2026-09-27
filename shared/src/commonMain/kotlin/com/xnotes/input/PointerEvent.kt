package com.xnotes.input

/**
 * One batch of pointer input, as the canvas controllers read it. The shape (and every constant
 * value) follows Android's `MotionEvent`, so the Android host wraps its events without copying and
 * other hosts (Swing, Qt) build them from their own tablet/mouse/touch events.
 *
 * Coordinates are viewport pixels; [eventTime] and the historical times are milliseconds on a
 * monotonic clock. Samples the platform coalesced since the previous event are exposed as history
 * (`0 until historySize`, oldest first), each pointer's current sample via [getX]/[getY]/[getPressure].
 */
interface PointerEvent {
    /** One of the `ACTION_*` constants. */
    val actionMasked: Int

    /** For [ACTION_POINTER_DOWN]/[ACTION_POINTER_UP]: the index of the pointer that changed. */
    val actionIndex: Int
    val pointerCount: Int
    val eventTime: Long

    /** Bit set of `BUTTON_*` constants held during this event. */
    val buttonState: Int

    fun getPointerId(pointerIndex: Int): Int

    /** Index of the pointer with [pointerId], or -1 when it is not in this event. */
    fun findPointerIndex(pointerId: Int): Int

    /** One of the `TOOL_TYPE_*` constants. */
    fun getToolType(pointerIndex: Int): Int
    fun getX(pointerIndex: Int): Float
    fun getY(pointerIndex: Int): Float

    /** Normalised pen pressure, nominally 0..1 (1 for tools without pressure). */
    fun getPressure(pointerIndex: Int): Float

    val x: Float get() = getX(0)
    val y: Float get() = getY(0)

    val historySize: Int
    fun getHistoricalX(pointerIndex: Int, pos: Int): Float
    fun getHistoricalY(pointerIndex: Int, pos: Int): Float
    fun getHistoricalPressure(pointerIndex: Int, pos: Int): Float
    fun getHistoricalEventTime(pos: Int): Long

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        const val ACTION_HOVER_MOVE = 7
        const val ACTION_HOVER_ENTER = 9
        const val ACTION_HOVER_EXIT = 10
        const val ACTION_BUTTON_PRESS = 11
        const val ACTION_BUTTON_RELEASE = 12

        const val TOOL_TYPE_UNKNOWN = 0
        const val TOOL_TYPE_FINGER = 1
        const val TOOL_TYPE_STYLUS = 2
        const val TOOL_TYPE_MOUSE = 3
        const val TOOL_TYPE_ERASER = 4

        const val BUTTON_PRIMARY = 1
        const val BUTTON_SECONDARY = 2
        const val BUTTON_TERTIARY = 4
        const val BUTTON_STYLUS_PRIMARY = 32
        const val BUTTON_STYLUS_SECONDARY = 64
    }
}

/** Key codes the controllers react to, with Android's `KeyEvent` values. */
object KeyCodes {
    const val KEYCODE_F = 34
    const val KEYCODE_F21 = 334
    const val KEYCODE_STYLUS_BUTTON_PRIMARY = 308
    const val KEYCODE_STYLUS_BUTTON_SECONDARY = 309
    const val KEYCODE_STYLUS_BUTTON_TERTIARY = 310
    const val KEYCODE_STYLUS_BUTTON_TAIL = 311
}
