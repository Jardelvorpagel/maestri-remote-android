package app.maestri.remote

import android.app.Application
import app.maestri.wire.session.WireSession

class MaestriApp : Application() {

    lateinit var session: WireSession
        private set

    override fun onCreate() {
        super.onCreate()
        session = WireSession(this)
    }
}