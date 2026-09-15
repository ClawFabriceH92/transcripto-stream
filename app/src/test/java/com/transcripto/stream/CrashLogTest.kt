package com.transcripto.stream

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashLogTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        CrashLog.clear(context)
        Thread.setDefaultUncaughtExceptionHandler(null)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(null)
        CrashLog.clear(context)
    }

    private fun handler(): Thread.UncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()!!

    @Test
    fun chainsPreviousHandlerAndKeepsRootCause() {
        var seen: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, t -> seen = t }
        CrashLog.install(context)
        val boom = RuntimeException("plantage principal", IllegalStateException("cause racine"))
        handler().uncaughtException(Thread.currentThread(), boom)
        assertSame("le gestionnaire précédent est toujours appelé", boom, seen)
        val log = CrashLog.read(context)
        assertEquals(1, CrashLog.count(context))
        assertTrue(log, log.startsWith("=== "))
        assertTrue(log, log.contains(" · thread " + Thread.currentThread().name))
        assertTrue(log, log.contains("java.lang.RuntimeException: plantage principal"))
        assertTrue(log, log.contains("Caused by: java.lang.IllegalStateException: cause racine"))
        assertNotNull(CrashLog.file(context))
    }

    private fun deep(n: Int): Nothing = if (n == 0) throw IllegalArgumentException("tout en bas", ArithmeticException("origine")) else deep(n - 1)

    @Test
    fun longTracesKeepHeadAndCausedByOnly() {
        CrashLog.install(context)
        val t = try {
            deep(120)
        } catch (e: IllegalArgumentException) {
            e
        }
        handler().uncaughtException(Thread.currentThread(), t)
        val log = CrashLog.read(context)
        val atLines = log.lines().count { it.trimStart().startsWith("at ") }
        assertTrue("trace tronquée à la tête : $atLines lignes", atLines in 20..35)
        assertTrue(log, log.contains("Caused by: java.lang.ArithmeticException: origine"))
    }

    @Test
    fun newestFirstCappedAndClearable() {
        CrashLog.install(context)
        repeat(60) { i ->
            handler().uncaughtException(Thread.currentThread(), RuntimeException("plantage n°$i " + "x".repeat(5_000)))
        }
        val log = CrashLog.read(context)
        assertTrue(log.length <= 200_000)
        assertTrue("le plus récent en tête", log.indexOf("plantage n°59 ") < log.indexOf("plantage n°58 "))
        assertTrue(CrashLog.count(context) in 2..59)
        CrashLog.clear(context)
        assertEquals("", CrashLog.read(context))
        assertEquals(0, CrashLog.count(context))
        assertNull(CrashLog.file(context))
    }
}
