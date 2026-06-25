package neth.iecal.curbox.data.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The sync surface the UI talks to. The real implementation lives only in the
 * playstore flavor. The F-Droid flavor binds [NoopSyncProvider] through its own
 * [createSyncProvider], so no network or Supabase code is ever compiled in.
 */
interface SyncProvider {
    val isAvailable: Boolean
    val status: StateFlow<SyncStatus>

    fun start()
    suspend fun signUp(email: String, password: String)
    suspend fun signIn(email: String, password: String)
    suspend fun verifySignupCode(email: String, code: String)
    suspend fun resendSignupCode(email: String)
    suspend fun sendPasswordReset(email: String)
    suspend fun resetPassword(email: String, code: String, newPassword: String)
    suspend fun signOut()
    suspend fun setPassphrase(passphrase: String)
    suspend fun unlock(passphrase: String)
    suspend fun makePairingCode(): String
    suspend fun pairWithCode(payload: String)
    suspend fun refresh()
    suspend fun pushNow()

    /** Website usage synced from other devices (e.g. the browser extension), domain to milliseconds, for an ISO date. */
    suspend fun remoteWebsiteUsage(dateIso: String): Map<String, Long>

    /** App usage synced from a user's other Android devices, package name to milliseconds, for an ISO date. */
    suspend fun remoteAppUsage(dateIso: String): Map<String, Long>
}

/** Sentinel package used for the synthetic "Synced browsing" row in the usage list. */
const val SYNCED_WEB_PACKAGE = "__curbox_synced_web__"

data class SyncStatus(
    val signedIn: Boolean = false,
    val email: String? = null,
    val hasVault: Boolean = false,
    val unlocked: Boolean = false,
    val deviceId: String? = null,
    val lastSync: Long? = null,
    val error: String? = null,
    // Set after a sign up (or a sign in for an unconfirmed account) so the UI can
    // ask for the emailed code.
    val pendingEmail: String? = null,
)

/** Used by the F-Droid flavor. */
object NoopSyncProvider : SyncProvider {
    override val isAvailable = false
    override val status = MutableStateFlow(SyncStatus())

    override fun start() {}
    override suspend fun signUp(email: String, password: String) = unsupported()
    override suspend fun signIn(email: String, password: String) = unsupported()
    override suspend fun verifySignupCode(email: String, code: String) = unsupported()
    override suspend fun resendSignupCode(email: String) = unsupported()
    override suspend fun sendPasswordReset(email: String) = unsupported()
    override suspend fun resetPassword(email: String, code: String, newPassword: String) = unsupported()
    override suspend fun signOut() {}
    override suspend fun setPassphrase(passphrase: String) = unsupported()
    override suspend fun unlock(passphrase: String) = unsupported()
    override suspend fun makePairingCode(): String = unsupported()
    override suspend fun pairWithCode(payload: String) = unsupported()
    override suspend fun refresh() {}
    override suspend fun pushNow() {}
    override suspend fun remoteWebsiteUsage(dateIso: String): Map<String, Long> = emptyMap()
    override suspend fun remoteAppUsage(dateIso: String): Map<String, Long> = emptyMap()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Sync is not available in this build")
}

object SyncGateway {
    @Volatile
    private var providerRef: SyncProvider? = null

    fun init(context: Context) {
        if (providerRef != null) return
        synchronized(this) {
            if (providerRef == null) providerRef = createSyncProvider(context.applicationContext)
        }
    }

    val provider: SyncProvider
        get() = providerRef ?: NoopSyncProvider
}
