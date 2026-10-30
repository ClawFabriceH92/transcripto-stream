package com.transcripto.stream.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateScheduleTest {

    private val heure = 60 * 60_000L

    @Test
    fun `une verification manuelle passe toujours`() {
        assertTrue(UpdateSchedule.isDue(manual = true, lastCheckAt = 999_999L, now = 1_000_000L))
    }

    @Test
    fun `la premiere verification automatique passe`() {
        assertTrue(UpdateSchedule.isDue(manual = false, lastCheckAt = UpdateSchedule.NEVER, now = 5_000L))
    }

    @Test
    fun `deux verifications rapprochees sont bloquees`() {
        assertFalse(UpdateSchedule.isDue(manual = false, lastCheckAt = 1_000L, now = 1_000L + heure / 2))
    }

    @Test
    fun `une heure apres la derniere verification, elle repasse`() {
        assertTrue(UpdateSchedule.isDue(manual = false, lastCheckAt = 1_000L, now = 1_000L + heure))
    }
}
