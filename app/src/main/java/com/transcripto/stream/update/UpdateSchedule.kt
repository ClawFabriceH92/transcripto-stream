package com.transcripto.stream.update

/**
 * Quand une vérification automatique peut avoir lieu : au lancement, puis au
 * plus une fois par heure. Une vérification qui n'a pas atteint GitHub ne
 * consomme pas le créneau (l'appelant remet l'horodatage à zéro en cas d'échec).
 */
internal object UpdateSchedule {

    const val MIN_INTERVAL_MS = 60 * 60_000L
    const val NEVER = 0L

    fun isDue(manual: Boolean, lastCheckAt: Long, now: Long): Boolean =
        manual || lastCheckAt == NEVER || now - lastCheckAt >= MIN_INTERVAL_MS
}
