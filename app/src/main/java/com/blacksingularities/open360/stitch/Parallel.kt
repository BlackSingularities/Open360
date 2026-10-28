package com.blacksingularities.open360.stitch

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.math.max
import kotlin.math.min

/** Mała pula wątków do rozdzielania pracy na rdzenie (zszywanie wierszami, próby obrotów). */
object Parallel {
    val threads = max(1, min(4, Runtime.getRuntime().availableProcessors() - 1))
    private val pool = Executors.newFixedThreadPool(threads, ThreadFactory { r ->
        Thread(r, "open360-calc").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
    })

    /** Wywołuje [body] dla podzakresów [from, to) – w kawałkach, na wszystkich wątkach puli. */
    fun forRange(from: Int, to: Int, body: (Int, Int) -> Unit) {
        val n = to - from
        if (n <= 0) return
        if (threads == 1 || n < 8) {
            body(from, to)
            return
        }
        val parts = min(n, threads * 2)
        val tasks = (0 until parts).map { k ->
            val a = from + n * k / parts
            val b = from + n * (k + 1) / parts
            Callable { body(a, b) }
        }
        for (f in pool.invokeAll(tasks)) f.get()
    }
}
