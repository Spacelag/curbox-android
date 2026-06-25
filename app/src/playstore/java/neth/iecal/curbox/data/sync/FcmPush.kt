package neth.iecal.curbox.data.sync

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Thin wrapper around Firebase Cloud Messaging. Firebase is initialised by hand
 * from [FcmConfig] (no google-services plugin). Everything here is forgiving: if
 * FCM is not configured, or anything goes wrong, it quietly does nothing and the
 * app falls back to the realtime websocket and the periodic worker.
 */
object FcmPush {

    @Volatile private var initialised = false

    /** Safe to call repeatedly and from any process. Returns true once Firebase is up. */
    @Synchronized
    fun ensureInit(context: Context): Boolean {
        if (initialised) return true
        if (!FcmConfig.isConfigured) return false
        return try {
            val app = context.applicationContext
            if (FirebaseApp.getApps(app).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId(FcmConfig.PROJECT_ID)
                    .setApplicationId(FcmConfig.APP_ID)
                    .setApiKey(FcmConfig.API_KEY)
                    .setGcmSenderId(FcmConfig.SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(app, options)
            }
            initialised = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Current device token, or null if FCM is off or unavailable. Blocks, so call
     * from a background thread.
     */
    fun token(context: Context): String? {
        if (!ensureInit(context)) return null
        return try {
            Tasks.await(FirebaseMessaging.getInstance().token)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
