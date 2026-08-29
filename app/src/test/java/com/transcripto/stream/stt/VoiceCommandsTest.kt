package com.transcripto.stream.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {

    @Test
    fun basicPunctuation() {
        assertEquals(
            "bonjour, comment allez-vous ?",
            VoiceCommands.apply("bonjour virgule comment allez-vous point d'interrogation"),
        )
    }

    @Test
    fun sentenceEndAndNewline() {
        assertEquals(
            "merci à tous.\nordre du jour :",
            VoiceCommands.apply("merci à tous point à la ligne ordre du jour deux points"),
        )
    }

    @Test
    fun newParagraphAndExclamation() {
        assertEquals(
            "c'est validé !\n\nsuite du dossier.",
            VoiceCommands.apply("c'est validé point d'exclamation nouveau paragraphe suite du dossier point"),
        )
    }

    @Test
    fun longestRuleWinsOverPoint() {
        // « point virgule » ne doit pas être mangé par « point »
        assertEquals("a ; b.", VoiceCommands.apply("a point virgule b point"))
    }

    @Test
    fun pluralAndConjugatedFormsAreUntouched() {
        // « points » et « pointent » (une lettre suit la commande) ne sont pas convertis
        assertEquals(
            "les points de contrôle pointent vers le nord",
            VoiceCommands.apply("les points de contrôle pointent vers le nord"),
        )
    }

    @Test
    fun caseInsensitive() {
        assertEquals("d'accord.", VoiceCommands.apply("d'accord Point"))
    }
}
