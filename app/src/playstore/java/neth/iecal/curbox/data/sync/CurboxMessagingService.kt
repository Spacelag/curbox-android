package neth.iecal.curbox.data.sync

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the content-less "sync" ping. The message carries no data, so all we
 * do is pull from Supabase and decrypt locally, exactly like the realtime path.
 * The system wakes the (main) process to deliver this even when the app was
 * killed, which is what makes background focus changes land instantly.
 */
class CurboxMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d("fcm","turning on sync")
        SyncGateway.init(applicationContext)
        val provider = SyncGateway.provider
        if (!provider.isAvailable) return
        // wake() makes sure we are signed in (the process may have been cold) and
        // then pulls. Falls back to a plain refresh if somehow not the real provider.
        val playstore = provider as? PlaystoreSyncProvider
        if (playstore != null) {
            playstore.wake()
        } else {
            CoroutineScope(Dispatchers.IO).launch { runCatching { provider.refresh() } }
        }
    }

    override fun onNewToken(token: String) {
        SyncGateway.init(applicationContext)
        (SyncGateway.provider as? PlaystoreSyncProvider)?.onFcmToken(token)
    }
}
