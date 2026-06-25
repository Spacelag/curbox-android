package neth.iecal.curbox.data.sync

/**
 * Firebase project identifiers, pasted from the Firebase console so we can
 * initialise Firebase by hand and skip the google-services Gradle plugin (and
 * the google-services.json file it needs).
 *
 * Find these in Firebase console > Project settings > General, under "Your apps"
 * (the Android app's SDK setup) and the project's general info:
 *   projectId  : the Firebase project id (e.g. "curbox-app")
 *   appId      : the Android "App ID" (mobilesdk_app_id, like 1:123...:android:abc)
 *   apiKey     : the Android app's "current_key" / API key
 *   senderId   : the "Project number" (messaging/sender id)
 *
 * Leave any of these blank to fully disable FCM: the app then just runs on the
 * realtime websocket + worker as before. Nothing here is secret; these ids are
 * shipped in every Firebase Android app.
 */
object FcmConfig {
    const val PROJECT_ID = "curbox-e6b94"
    const val APP_ID = "1:473249600577:android:db87484e8af0393c9122c0"
    const val API_KEY = "AIzaSyDQyiAGMpP0JHx8A8CJl42f9wOH59tvyu0"
    const val SENDER_ID = "473249600577"

    val isConfigured: Boolean
        get() = PROJECT_ID.isNotBlank() && APP_ID.isNotBlank() && API_KEY.isNotBlank() && SENDER_ID.isNotBlank()
}
