package app.maestri.remote

import android.app.Application
import app.maestri.wire.session.WireSession
import app.maestri.ui.settings.AppLockStore

class MaestriApp : Application() {

    lateinit var session: WireSession
        private set

    val appLock: AppLockStore by lazy { AppLockStore(this) }

    override fun onCreate() {
        super.onCreate()
        session = WireSession(this)
    }
}