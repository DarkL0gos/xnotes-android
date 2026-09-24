package com.xnotes.input

/** One pointer's sample in a [SimplePointerEvent]. */
data class PointerSample(
    val id: Int,
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val toolType: Int = PointerEvent.TOOL_TYPE_MOUSE,
)

/**
 * A plain [PointerEvent] for hosts that build input from their own events (Swing, Qt) and for
 * tests. [history] holds coalesced earlier samples of the same pointers, oldest first, each with
 * its time; hosts without coalescing leave it empty.
 */
class SimplePointerEvent(
    override val actionMasked: Int,
    private val pointers: List<PointerSample>,
    override val eventTime: Long,
    override val actionIndex: Int = 0,
    override val buttonState: Int = 0,
    private val history: List<Pair<Long, List<PointerSample>>> = emptyList(),
) : PointerEvent {
    init {
        require(pointers.isNotEmpty()) { "an event needs at least one pointer" }
        require(history.all { it.second.size == pointers.size }) { "history must sample every pointer" }
    }

    override val pointerCount get() = pointers.size
    override fun getPointerId(pointerIndex: Int) = pointers[pointerIndex].id
    override fun findPointerIndex(pointerId: Int) = pointers.indexOfFirst { it.id == pointerId }
    override fun getToolType(pointerIndex: Int) = pointers[pointerIndex].toolType
    override fun getX(pointerIndex: Int) = pointers[pointerIndex].x
    override fun getY(pointerIndex: Int) = pointers[pointerIndex].y
    override fun getPressure(pointerIndex: Int) = pointers[pointerIndex].pressure

    override val historySize get() = history.size
    override fun getHistoricalX(pointerIndex: Int, pos: Int) = history[pos].second[pointerIndex].x
    override fun getHistoricalY(pointerIndex: Int, pos: Int) = history[pos].second[pointerIndex].y
    override fun getHistoricalPressure(pointerIndex: Int, pos: Int) = history[pos].second[pointerIndex].pressure
    override fun getHistoricalEventTime(pos: Int) = history[pos].first
}
