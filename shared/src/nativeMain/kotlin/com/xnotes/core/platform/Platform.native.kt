package com.xnotes.core.platform

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.math.abs
import kotlin.math.pow
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlin.native.identityHashCode
import kotlin.time.TimeSource

actual fun interface Runnable {
    actual fun run()
}

private val clockOrigin = TimeSource.Monotonic.markNow()

actual fun monotonicNanos(): Long = clockOrigin.elapsedNow().inWholeNanoseconds

/**
 * A reentrant spin lock. The core only guards short sections (a set add, a sweep of released
 * pages), so spinning costs less than parking a thread would.
 */
@OptIn(ExperimentalAtomicApi::class)
actual class Lock actual constructor() {
    @PublishedApi internal val owner = AtomicLong(0L)

    @PublishedApi internal var depth = 0
}

@OptIn(ExperimentalAtomicApi::class, ObsoleteWorkersApi::class)
@PublishedApi
internal fun currentThreadKey(): Long = Worker.current.id.toLong() + 1L

@OptIn(ExperimentalAtomicApi::class)
actual inline fun <T> withLock(lock: Lock, block: () -> T): T {
    val me = currentThreadKey()
    if (lock.owner.load() == me) {
        lock.depth++
        try {
            return block()
        } finally {
            lock.depth--
        }
    }
    while (!lock.owner.compareAndSet(0L, me)) {
        // spin: sections are short
    }
    try {
        return block()
    } finally {
        lock.owner.store(0L)
    }
}

actual fun <K, V> identityMap(): MutableMap<K, V> = IdentityMap()

actual fun <K, V> identityMap(expectedMaxSize: Int): MutableMap<K, V> = IdentityMap(expectedMaxSize)

/** Keys compared by reference, hashed by identity. */
private class IdentityKey(val ref: Any?) {
    @OptIn(ExperimentalNativeApi::class)
    override fun hashCode(): Int = ref.identityHashCode()

    override fun equals(other: Any?): Boolean = other is IdentityKey && other.ref === ref
}

private class IdentityMap<K, V>(expectedMaxSize: Int = 21) : AbstractMutableMap<K, V>() {
    private val backing = HashMap<IdentityKey, V>(expectedMaxSize * 2)

    override val size: Int get() = backing.size
    override fun containsKey(key: K): Boolean = backing.containsKey(IdentityKey(key))
    override fun get(key: K): V? = backing[IdentityKey(key)]
    override fun put(key: K, value: V): V? = backing.put(IdentityKey(key), value)
    override fun remove(key: K): V? = backing.remove(IdentityKey(key))
    override fun clear() = backing.clear()

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
            override val size: Int get() = backing.size
            override fun add(element: MutableMap.MutableEntry<K, V>): Boolean =
                throw UnsupportedOperationException("add an entry through put()")

            override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
                val inner = backing.entries.iterator()
                return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                    override fun hasNext() = inner.hasNext()
                    override fun remove() = inner.remove()

                    @Suppress("UNCHECKED_CAST")
                    override fun next(): MutableMap.MutableEntry<K, V> {
                        val e = inner.next()
                        return object : MutableMap.MutableEntry<K, V> {
                            override val key: K get() = e.key.ref as K
                            override val value: V get() = e.value
                            override fun setValue(newValue: V): V = e.setValue(newValue)
                        }
                    }
                }
            }
        }
}

/** Half-up to [decimals] places with a '.' separator (the C locale); display only. */
actual fun formatDecimal(value: Double, decimals: Int): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val scale = 10.0.pow(decimals)
    val scaled = (abs(value) * scale + 0.5).toLong()
    val negative = value < 0 && scaled != 0L
    val digits = scaled.toString().padStart(decimals + 1, '0')
    val whole = digits.dropLast(decimals)
    val frac = digits.takeLast(decimals)
    return buildString {
        if (negative) append('-')
        append(whole)
        if (decimals > 0) append('.').append(frac)
    }
}

@OptIn(ExperimentalAtomicApi::class)
actual class IdCounter actual constructor() {
    private val value = AtomicLong(0L)

    actual fun next(): Long = value.addAndFetch(1L)
}
