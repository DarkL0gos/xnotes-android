package com.xnotes.core.platform

actual typealias Runnable = java.lang.Runnable

actual fun monotonicNanos(): Long = System.nanoTime()

actual typealias Lock = Any

actual inline fun <T> withLock(lock: Lock, block: () -> T): T = synchronized(lock, block)

actual fun <K, V> identityMap(): MutableMap<K, V> = java.util.IdentityHashMap()

actual fun <K, V> identityMap(expectedMaxSize: Int): MutableMap<K, V> = java.util.IdentityHashMap(expectedMaxSize)

actual fun formatDecimal(value: Double, decimals: Int): String = "%.${decimals}f".format(value)

actual class IdCounter actual constructor() {
    private val value = java.util.concurrent.atomic.AtomicLong(0L)

    actual fun next(): Long = value.incrementAndGet()
}

actual fun javaDoubleToString(v: Double): String = v.toString()
