package app.maestri.remote

import android.app.Application
import app.maestri.update.UpdateClient
import app.maestri.update.UpdateController
import app.maestri.update.manifestUrls
import app.maestri.wire.session.WireSession
import app.maestri.ui.settings.AppLockStore

class MaestriApp : Application() {

    lateinit var session: WireSession
        private set

    val appLock: AppLockStore by lazy { AppLockStore(this) }

    /**
     * Update feature: release manifest + system installer. The manifest is looked up on
     * the paired host's relay first (reachable over Tailscale on its own) and on the
     * public releases URL second.
     */
    val updates: UpdateController by lazy {
        UpdateController(
            this,
            UpdateClient(manifests = { manifestUrls(UpdateClient.LATEST_URL, session.pairing.value?.host) }),
        )
    }

    override fun onCreate() {
        super.onCreate()
        session = WireSession(this)
    }
}