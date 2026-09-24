package com.xnotes.platform

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.xnotes.input.FrameCallback
import com.xnotes.input.PointerEvent
import com.xnotes.input.UiScheduler

/**
 * A [PointerEvent] view of a [MotionEvent], without copying. One instance serves a whole event
 * stream: [wrap] repoints it, which is safe because the controllers read an event synchronously
 * and never keep it. The constants already share Android's values, so nothing is translated.
 */
class AndroidPointerEvent : PointerEvent {
    private lateinit var e: MotionEvent

    fun wrap(event: MotionEvent): AndroidPointerEvent {
        e = event
        return this
    }

    override val actionMasked get() = e.actionMasked
    override val actionIndex get() = e.actionIndex
    override val pointerCount get() = e.pointerCount
    override val eventTime get() = e.eventTime
    override val buttonState get() = e.buttonState
    override fun getPointerId(pointerIndex: Int) = e.getPointerId(pointerIndex)
    override fun findPointerIndex(pointerId: Int) = e.findPointerIndex(pointerId)
    override fun getToolType(pointerIndex: Int) = e.getToolType(pointerIndex)
    override fun getX(pointerIndex: Int) = e.getX(pointerIndex)
    override fun getY(pointerIndex: Int) = e.getY(pointerIndex)
    override fun getPressure(pointerIndex: Int) = e.getPressure(pointerIndex)
    override val x get() = e.x
    override val y get() = e.y
    override val historySize get() = e.historySize
    override fun getHistoricalX(pointerIndex: Int, pos: Int) = e.getHistoricalX(pointerIndex, pos)
    override fun getHistoricalY(pointerIndex: Int, pos: Int) = e.getHistoricalY(pointerIndex, pos)
    override fun getHistoricalPressure(pointerIndex: Int, pos: Int) = e.getHistoricalPressure(pointerIndex, pos)
    override fun getHistoricalEventTime(pos: Int) = e.getHistoricalEventTime(pos)
}

/**
 * The main thread's [Handler] and [Choreographer] as a [UiScheduler]. Both are created on first use
 * (always a gesture on the main thread), so controllers stay constructible off-device.
 */
class AndroidUiScheduler : UiScheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val choreographer by lazy { Choreographer.getInstance() }

    /** Choreographer callbacks are framework objects; keep one per shared callback so reposts reuse it. */
    private val frames = HashMap<FrameCallback, Choreographer.FrameCallback>()

    override fun postDelayed(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }

    override fun removeCallbacks(task: Runnable) {
        handler.removeCallbacks(task)
    }

    override fun postFrameCallback(callback: FrameCallback) {
        val frame = frames.getOrPut(callback) { Choreographer.FrameCallback { callback.doFrame(it) } }
        choreographer.postFrameCallback(frame)
    }

    override val longPressTimeoutMs: Long get() = ViewConfiguration.getLongPressTimeout().toLong()
}
