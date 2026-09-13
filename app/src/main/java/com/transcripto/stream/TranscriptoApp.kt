package com.transcripto.stream

import android.app.Application

/** Point d'entrée de l'application : installe le journal local des plantages. */
class TranscriptoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
