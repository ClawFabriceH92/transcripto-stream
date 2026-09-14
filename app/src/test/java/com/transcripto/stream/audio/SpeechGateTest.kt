package com.transcripto.stream.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {

    private fun run(gate: SpeechGate, probs: List<Float>, frameMs: Int = 32): List<SpeechGate.Event?> =
        probs.map { gate.feed(it, frameMs) }

    @Test
    fun startsAfterMinSpeechAndEndsAfterMinSilence() {
        val gate = SpeechGate(minSilenceMs = 500, minSpeechMs = 250)
        // 8 trames de 32 ms = 256 ms de parole → départ à la 8e
        val start = run(gate, List(8) { 0.9f })
        assertEquals(List(7) { null } + SpeechGate.Event.SPEECH_START, start)
        assertTrue(gate.speaking)
        // 10 trames de silence = 320 ms : pas encore la fin
        assertTrue(run(gate, List(10) { 0.1f }).all { it == null })
        assertTrue(gate.speaking)
        // 6 trames de plus (512 ms cumulés) → fin
        val end = run(gate, List(6) { 0.1f })
        assertEquals(SpeechGate.Event.SPEECH_END, end.last())
        assertFalse(gate.speaking)
    }

    @Test
    fun shortBlipsDoNotStartAndBriefPausesDoNotEnd() {
        val gate = SpeechGate()
        // 5 trames (160 ms) puis silence : jamais parti
        assertTrue(run(gate, List(5) { 0.9f } + List(5) { 0.0f }).all { it == null })
        assertFalse(gate.speaking)
        run(gate, List(10) { 0.9f })
        assertTrue(gate.speaking)
        // Pause de 300 ms puis reprise : le compteur de silence repart de zéro
        assertTrue(run(gate, List(9) { 0.2f } + listOf(0.8f) + List(9) { 0.2f }).all { it == null })
        assertTrue(gate.speaking)
    }

    @Test
    fun hysteresisBetweenThresholds() {
        val gate = SpeechGate(startThreshold = 0.5f, endThreshold = 0.35f, minSpeechMs = 32)
        assertEquals(SpeechGate.Event.SPEECH_START, gate.feed(0.6f, 32))
        // 0.4 est sous le seuil d'entrée mais au-dessus du seuil de sortie : on reste en parole
        assertNull(gate.feed(0.4f, 1000))
        assertTrue(gate.speaking)
        assertEquals(SpeechGate.Event.SPEECH_END, gate.feed(0.3f, 1000))
        gate.reset()
        assertFalse(gate.speaking)
    }
}
