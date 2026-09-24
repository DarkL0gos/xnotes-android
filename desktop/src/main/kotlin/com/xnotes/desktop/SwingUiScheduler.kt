package com.xnotes.desktop

import com.xnotes.input.FrameCallback
import com.xnotes.input.UiScheduler
import java.util.IdentityHashMap
import javax.swing.Timer

/**
 * [UiScheduler] on the Swing event thread. Delayed tasks are one-shot [Timer]s; frame callbacks are
 * batched onto a ~60 Hz tick that runs while any are pending, which is what the fling and fade
 * animations expect from a display frame.
 */
internal class SwingUiScheduler : UiScheduler {
    private val delayed = IdentityHashMap<Runnable, Timer>()
    private val frames = ArrayList<FrameCallback>()
    private val frameTick = Timer(FRAME_MS) { runFrame() }.apply { isRepeats = false }

    override fun postDelayed(task: Runnable, delayMs: Long) {
        // Like Handler, a task may be posted more than once; the latest posting replaces the timer.
        delayed.remove(task)?.stop()
        val timer = Timer(delayMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) {
            delayed.remove(task)
            task.run()
        }
        timer.isRepeats = false
        delayed[task] = timer
        timer.start()
    }

    override fun removeCallbacks(task: Runnable) {
        delayed.remove(task)?.stop()
    }

    override fun postFrameCallback(callback: FrameCallback) {
        frames.add(callback)
        if (!frameTick.isRunning) frameTick.start()
    }

    private fun runFrame() {
        val due = frames.toList()
        frames.clear()
        val now = System.nanoTime()
        due.forEach { it.doFrame(now) }
    }

    /** Stop every pending timer (the window is closing). */
    fun shutdown() {
        delayed.values.forEach { it.stop() }
        delayed.clear()
        frames.clear()
        frameTick.stop()
    }

    private companion object {
        const val FRAME_MS = 16
    }
}
