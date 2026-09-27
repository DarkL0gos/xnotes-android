package com.xnotes.input

/** Called once on the next display frame with that frame's time in nanoseconds. */
fun interface FrameCallback {
    fun doFrame(frameTimeNanos: Long)
}

/**
 * The UI thread's timers, as the controllers use them: delayed tasks (long press, dwell) and
 * per-frame callbacks (fling, fades). Everything runs on the UI thread; implementations are Android's
 * `Handler` + `Choreographer`, a Swing `Timer`, or Qt's event loop.
 */
interface UiScheduler {
    fun postDelayed(task: Runnable, delayMs: Long)
    fun removeCallbacks(task: Runnable)
    fun postFrameCallback(callback: FrameCallback)

    /** The platform's long-press delay (user-adjustable on Android, for accessibility). */
    val longPressTimeoutMs: Long get() = 400L
}
