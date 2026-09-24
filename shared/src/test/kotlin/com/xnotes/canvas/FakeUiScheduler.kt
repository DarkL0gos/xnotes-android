package com.xnotes.canvas

import com.xnotes.input.FrameCallback
import com.xnotes.input.UiScheduler

/** A manual clock: tasks and frame callbacks run only when a test advances it. */
class FakeUiScheduler : UiScheduler {
    var nowMs = 0L
        private set
    private val tasks = ArrayList<Pair<Long, Runnable>>()
    private val frames = ArrayList<FrameCallback>()

    override fun postDelayed(task: Runnable, delayMs: Long) {
        tasks.add(nowMs + delayMs to task)
    }

    override fun removeCallbacks(task: Runnable) {
        tasks.removeAll { it.second === task }
    }

    override fun postFrameCallback(callback: FrameCallback) {
        frames.add(callback)
    }

    /** Move the clock forward, running every task that falls due, in order. */
    fun advance(ms: Long) {
        val until = nowMs + ms
        while (true) {
            val next = tasks.filter { it.first <= until }.minByOrNull { it.first } ?: break
            tasks.remove(next)
            nowMs = next.first
            next.second.run()
        }
        nowMs = until
    }

    /** Run the callbacks posted for the next frame (they may post themselves again). */
    fun frame(frameTimeNanos: Long = nowMs * 1_000_000L) {
        val due = frames.toList()
        frames.clear()
        due.forEach { it.doFrame(frameTimeNanos) }
    }
}
