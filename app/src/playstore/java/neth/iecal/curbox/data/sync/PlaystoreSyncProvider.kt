package neth.iecal.curbox.data.sync

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.AppUsageEntity
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.DataStoreManager
import org.json.JSONObject

/**
 * The real sync engine, present only in the Play Store flavor. Config syncs both
 * ways across a user's phones (last write wins). Website and app usage are
 * pushed per device so other devices and the browser extension can show unified
 * totals without double counting.
 */
@OptIn(FlowPreview::class)
class PlaystoreSyncProvider(private val context: Context) : SyncProvider {

    private val gson = Gson()
    // Lazy so nothing heavy (Keystore prefs, Room, DataStore) runs on the main
    // thread during Application.onCreate. These first touch inside start().
    private val rest by lazy { SupabaseRest() }
    private val keys by lazy { SecureKeyStore(context) }
    private val db by lazy { AppDatabase.getInstance(context) }
    private val dataStore by lazy { DataStoreManager.getSettingsDataStore(context, gson) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val NS_ANDROID_CONFIG = "android_config"
    private val NS_USAGE_WEB = "usage_web"
    private val NS_USAGE_APP = "usage_app"
    private val NS_FOCUS = "focus_state"
    private val NS_FOCUS_GROUPS = "focus_groups"

    private var session: SupabaseRest.Session? = null
    private var dek: ByteArray? = null
    // Whether a passphrase (vault) already exists on the server for this account.
    // Distinct from being unlocked: a second device has a vault but no local key
    // yet, so the UI must offer to UNLOCK or pair, not create a new passphrase.
    private var vaultExists: Boolean = false
    private var realtime: RealtimeClient? = null
    @Volatile private var realtimeConnected = false
    private var pollStarted = false
    private var lastConfigJson: String? = null
    private var lastFocusJson: String? = null
    private val injectedGroupIds = HashSet<String>()
    // Last synced canonical JSON per focus group id, so we push only real changes
    // and never bounce an applied remote group straight back up.
    private val focusGroupShadow = HashMap<String, String>()
    private val pushedHashes = HashMap<String, Int>()
    private var observersStarted = false

    private val _status = MutableStateFlow(SyncStatus())
    override val status: StateFlow<SyncStatus> = _status
    override val isAvailable = true

    override fun start() {
        scope.launch {
            runCatching { SyncWorker.schedule(context) }
            val refresh = keys.refreshToken ?: return@launch
            try {
                session = rest.refresh(refresh).also { persistSession(it) }
                keys.dekB64?.let { dek = CryptoBox.fromBase64Url(it) }
                onSignedIn()
            } catch (e: Exception) {
                // A refresh token the server rejected will never recover, so wipe
                // the local session and drop back to a clean signed out state. A
                // plain network outage (e.g. "unable to resolve host") is kept so
                // the next start can retry once we are back online.
                if (isRejectedToken(e)) {
                    keys.clear()
                    session = null
                    dek = null
                }
                publishStatus(error = e.message)
            }
        }
    }

    // Auth -----------------------------------------------------------------

    private var pendingEmail: String? = null

    override suspend fun signUp(email: String, password: String) = withContext(Dispatchers.IO) {
        val s = rest.signUp(email, password)
        if (s == null) {
            pendingEmail = email
            publishStatus()
        } else {
            session = s
            persistSession(s)
            pendingEmail = null
            onSignedIn()
        }
    }

    override suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val s = try {
            rest.signIn(email, password)
        } catch (e: Exception) {
            if (e.message?.contains("confirm", ignoreCase = true) == true) {
                pendingEmail = email
                publishStatus()
                return@withContext
            }
            throw e
        }
        session = s
        persistSession(s)
        pendingEmail = null
        onSignedIn()
    }

    override suspend fun verifySignupCode(email: String, code: String) = withContext(Dispatchers.IO) {
        val s = rest.verifyOtp(email, code.trim(), "signup")
        session = s
        persistSession(s)
        pendingEmail = null
        onSignedIn()
    }

    override suspend fun resendSignupCode(email: String) = withContext(Dispatchers.IO) {
        rest.resend(email, "signup")
    }

    override suspend fun sendPasswordReset(email: String) = withContext(Dispatchers.IO) {
        rest.recover(email)
    }

    override suspend fun resetPassword(email: String, code: String, newPassword: String) = withContext(Dispatchers.IO) {
        val s = rest.verifyOtp(email, code.trim(), "recovery")
        rest.updatePassword(s, newPassword)
        session = s
        persistSession(s)
        pendingEmail = null
        onSignedIn()
    }

    override suspend fun signOut() = withContext(Dispatchers.IO) {
        stopRealtime()
        keys.clear()
        session = null
        dek = null
        vaultExists = false
        lastConfigJson = null
        lastFocusJson = null
        pendingEmail = null
        pushedHashes.clear()
        injectedGroupIds.clear()
        focusGroupShadow.clear()
        // Forget other devices' usage so a fresh sign in does not show stale
        // numbers from the previous account.
        runCatching { RemoteUsageStore(context).clear() }
        publishStatus()
    }

    // Vault and unlock -----------------------------------------------------

    override suspend fun setPassphrase(passphrase: String) = withContext(Dispatchers.IO) {
        val s = requireSession()
        if (rest.getVault(s) != null) throw IllegalStateException("a passphrase already exists, unlock instead")
        val salt = CryptoBox.randomSalt()
        val kek = CryptoBox.deriveKekBytes(passphrase, salt, CryptoBox.DEFAULT_KDF_PARAMS)
        val newDek = CryptoBox.randomDek()
        val wrapped = CryptoBox.wrapDek(kek, newDek, s.userId)
        rest.insertVault(s, CryptoBox.toBase64Url(salt), paramsJson(), CryptoBox.toBase64Url(wrapped))
        adoptDek(newDek)
        onSignedIn()
    }

    override suspend fun unlock(passphrase: String) = withContext(Dispatchers.IO) {
        val s = requireSession()
        val vault = rest.getVault(s) ?: throw IllegalStateException("no passphrase set yet")
        val params = gson.fromJson(vault.paramsJson, JsonObject::class.java)
        val kek = CryptoBox.deriveKekBytes(
            passphrase,
            CryptoBox.fromBase64Url(vault.saltB64),
            CryptoBox.KdfParams(
                iterations = params.get("iterations").asInt,
                dkLenBits = params.get("dkLenBits").asInt,
            ),
        )
        val unwrapped = try {
            CryptoBox.unwrapDek(kek, CryptoBox.fromBase64Url(vault.wrappedB64), s.userId)
        } catch (e: Exception) {
            throw IllegalStateException("that passphrase did not work")
        }
        adoptDek(unwrapped)
        onSignedIn()
    }

    override suspend fun makePairingCode(): String {
        val s = requireSession()
        val d = dek ?: throw IllegalStateException("unlock first")
        return CryptoBox.buildPairingPayload(s.userId, d)
    }

    override suspend fun pairWithCode(payload: String) = withContext(Dispatchers.IO) {
        val s = requireSession()
        val pairing = CryptoBox.parsePairingPayload(payload)
        if (pairing.userId != s.userId) throw IllegalStateException("this code is for a different account")
        adoptDek(pairing.dek)
        onSignedIn()
    }

    override suspend fun refresh() = withContext(Dispatchers.IO) { pullSinceCursor() }

    override suspend fun remoteWebsiteUsage(dateIso: String): Map<String, Long> = withContext(Dispatchers.IO) {
        RemoteUsageStore(context).websiteTotals(dateIso)
    }

    override suspend fun remoteAppUsage(dateIso: String): Map<String, Long> = withContext(Dispatchers.IO) {
        RemoteUsageStore(context).appTotals(dateIso)
    }

    override suspend fun pushNow() = withContext(Dispatchers.IO) {
        pushConfig()
        pushUsage()
    }

    // Internals ------------------------------------------------------------

    private fun requireSession(): SupabaseRest.Session = session ?: throw IllegalStateException("sign in first")

    // True when the server explicitly rejected our token (vs a transient network
    // error), meaning retrying with the same token is pointless.
    private fun isRejectedToken(e: Exception): Boolean {
        val m = e.message?.lowercase() ?: return false
        return "invalid" in m || "expired" in m || "revoked" in m ||
            "refresh token" in m || "not found" in m || "jwt" in m
    }

    private fun persistSession(s: SupabaseRest.Session) {
        keys.accessToken = s.accessToken
        keys.refreshToken = s.refreshToken
    }

    private fun adoptDek(bytes: ByteArray) {
        dek = bytes
        keys.dekB64 = CryptoBox.toBase64Url(bytes)
    }

    private fun paramsJson(): String = gson.toJson(CryptoBox.DEFAULT_KDF_PARAMS)

    private suspend fun onSignedIn() {
        val s = session ?: return
        try {
            rest.upsertDevice(s, keys.deviceId, "android", "android")
        } catch (_: Exception) {
        }
        // Find out whether a passphrase already exists so the screen can ask to
        // unlock or pair instead of offering to make a new one. Holding the key
        // already implies a vault exists.
        vaultExists = if (dek != null) true else runCatching { rest.getVault(s) != null }.getOrElse { vaultExists }
        publishStatus()
        if (dek != null) {
            startObservers()
            startRealtime()
            startSafetyPoll()
            pullSinceCursor()
            pushConfig()
            runCatching { pushFocusGroups(dataStore.data.first()) }
            pushUsage()
            runCatching { pushFocusFrom(dataStore.data.first()) }
        }
        publishStatus()
    }

    // Realtime push: the moment another device writes a change, we hear about it
    // and pull, so a focus start elsewhere lands here in about a second.
    private fun startRealtime() {
        val s = session ?: return
        val existing = realtime
        if (existing != null) {
            existing.updateToken(s.accessToken)
            return
        }
        realtime = RealtimeClient(
            userId = s.userId,
            accessToken = s.accessToken,
            onChange = { scope.launch { pullSinceCursor() } },
            onConnected = { realtimeConnected = it },
        ).also { runCatching { it.start() } }
    }

    private fun stopRealtime() {
        runCatching { realtime?.stop() }
        realtime = null
        realtimeConnected = false
    }

    // A short backstop poll so sync stays quick even if the realtime socket is
    // unavailable (some networks block WebSockets). Far better than the 15 minute
    // worker for catching changes while the app is alive. Also keeps the access
    // token fresh so long lived sessions do not silently stop syncing.
    private fun startSafetyPoll() {
        if (pollStarted) return
        pollStarted = true
        scope.launch {
            while (true) {
                // When realtime is delivering, this is just a slow backstop; when
                // it is down (e.g. WebSockets blocked) it tightens up to stay quick.
                delay(if (realtimeConnected) 60_000 else 10_000)
                if (dek == null || session == null) continue
                ensureFreshToken()
                runCatching { pullSinceCursor() }
            }
        }
    }

    private suspend fun ensureFreshToken() {
        val s = session ?: return
        val refresh = keys.refreshToken ?: return
        if (System.currentTimeMillis() < s.expiresAt - 60_000) return
        runCatching {
            val ns = rest.refresh(refresh)
            session = ns
            persistSession(ns)
            realtime?.updateToken(ns.accessToken)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startObservers() {
        if (observersStarted) return
        observersStarted = true
        scope.launch {
            dataStore.data.debounce(1500).collect { settings ->
                if (dek == null) return@collect
                val norm = gson.toJson(normalize(settings))
                if (norm != lastConfigJson) {
                    lastConfigJson = norm
                    runCatching { pushConfigJson(norm) }.onFailure { publishStatus(error = it.message) }
                }
                runCatching { pushFocusGroups(settings) }
            }
        }
        // Focus start and stop push immediately, with no debounce, so the other
        // device reacts right away instead of waiting out the config window.
        scope.launch {
            dataStore.data
                .distinctUntilChangedBy { it.activeManualFocusGroupId }
                .collect { settings ->
                    if (dek != null) runCatching { pushFocusFrom(settings) }
                }
        }
        // Usage observers follow the current day. Without re-binding on a date
        // change, a phone left running past midnight would keep pushing
        // yesterday's numbers and never start on the new day until the next
        // worker run.
        scope.launch {
            currentDayFlow().flatMapLatest { native ->
                db.websiteStatsDao().observeStatsForDate(native).map { native to it }
            }.collect { (native, rows) ->
                if (dek != null) runCatching { pushWebRows(isoFor(native), rows) }
            }
        }
        scope.launch {
            currentDayFlow().flatMapLatest { native ->
                db.appUsageDao().observeForDate(native).map { native to it }
            }.collect { (native, rows) ->
                if (dek != null) runCatching { pushAppRows(isoFor(native), rows) }
            }
        }
    }

    // Emits the app's native date string now, then again whenever the calendar
    // day flips, so day bound database observers can re-bind to the new day.
    private fun currentDayFlow(): Flow<String> = flow {
        var last: String? = null
        while (true) {
            val today = todayNative()
            if (today != last) {
                last = today
                emit(today)
            }
            delay(60_000)
        }
    }

    // Both the active focus and the focus group definitions travel through their
    // own cross platform namespaces, not the per platform config, so they are
    // stripped from the config payload. Without this, an android-to-android pair
    // would sync groups twice and the two paths would fight.
    private fun normalize(s: Settings): Settings =
        s.copy(
            activeManualFocusGroupId = Pair(null, 0L),
            nextWebsiteRecheckTime = 0L,
            manualFocusGroups = emptyList(),
        )

    // Push -----------------------------------------------------------------

    private suspend fun pushConfig() {
        val settings = dataStore.data.first()
        val norm = gson.toJson(normalize(settings))
        lastConfigJson = norm
        pushConfigJson(norm)
    }

    private fun pushConfigJson(norm: String) {
        val s = session ?: return
        val d = dek ?: return
        val aad = CryptoBox.recordAad(s.userId, NS_ANDROID_CONFIG, "config")
        val blob = CryptoBox.encryptRecord(d, aad, norm)
        rest.upsertRecord(s, NS_ANDROID_CONFIG, "config", keys.deviceId, CryptoBox.toBase64Url(blob), System.currentTimeMillis())
    }

    private suspend fun pushUsage() {
        val native = todayNative()
        val iso = todayIso()
        runCatching { pushWebRows(iso, db.websiteStatsDao().getStatsForDate(native)) }
        runCatching { pushAppRows(iso, db.appUsageDao().getForDate(native)) }
    }

    // One record carries a whole day's web usage for this device, keyed by
    // deviceId:date, so a busy day is a single row instead of one per domain.
    private fun pushWebRows(date: String, rows: List<WebsiteStatsEntity>) {
        val s = session ?: return
        val d = dek ?: return
        val domains = JsonObject()
        for ((domain, group) in rows.groupBy { it.domain }) {
            val paths = JsonObject().apply { group.forEach { addProperty(it.urlIdentifier, it.totalTime) } }
            domains.add(domain, JsonObject().apply {
                addProperty("ms", group.sumOf { it.totalTime })
                add("paths", paths)
            })
        }
        val payload = JsonObject().apply {
            addProperty("date", date)
            addProperty("platform", "android")
            add("domains", domains)
        }
        pushUsageRecord(s, d, NS_USAGE_WEB, "${keys.deviceId}:$date", payload)
    }

    private fun pushAppRows(date: String, rows: List<AppUsageEntity>) {
        val s = session ?: return
        val d = dek ?: return
        val apps = JsonObject()
        for (row in rows) {
            apps.add(row.packageName, JsonObject().apply {
                addProperty("ms", row.totalTime)
                addProperty("launchCount", row.launchCount)
                addProperty("hourlyUsage", row.hourlyUsage)
            })
        }
        val payload = JsonObject().apply {
            addProperty("date", date)
            add("apps", apps)
        }
        pushUsageRecord(s, d, NS_USAGE_APP, "${keys.deviceId}:$date", payload)
    }

    private fun pushUsageRecord(s: SupabaseRest.Session, d: ByteArray, namespace: String, recordKey: String, payload: JsonObject) {
        val hashKey = "$namespace/$recordKey"
        if (pushedHashes[hashKey] == payload.hashCode()) return
        val aad = CryptoBox.recordAad(s.userId, namespace, recordKey)
        val blob = CryptoBox.encryptRecord(d, aad, payload.toString())
        rest.upsertRecord(s, namespace, recordKey, keys.deviceId, CryptoBox.toBase64Url(blob), System.currentTimeMillis())
        pushedHashes[hashKey] = payload.hashCode()
    }

    // Focus mode (cross platform) -----------------------------------------

    // Canonical JSON for the active focus. Arrays are sorted so push and apply
    // produce identical strings, which is how we suppress the echo loop.
    private fun buildFocusJson(
        active: Boolean,
        groupId: String,
        name: String,
        endsAt: Long,
        startedAt: Long,
        domains: List<String>,
        packages: List<String>,
        mode: String,
        exitable: Boolean,
    ): String {
        val o = JsonObject()
        o.addProperty("active", active)
        o.addProperty("groupId", groupId)
        o.addProperty("name", name)
        o.addProperty("endsAt", endsAt)
        o.addProperty("startedAt", startedAt)
        o.addProperty("mode", mode)
        o.addProperty("exitable", exitable)
        o.addProperty("origin", keys.deviceId)
        o.add("domains", com.google.gson.JsonArray().apply { domains.sorted().forEach { add(it) } })
        o.add("packages", com.google.gson.JsonArray().apply { packages.sorted().forEach { add(it) } })
        return o.toString()
    }

    private fun pushFocusFrom(settings: Settings) {
        val s = session ?: return
        val d = dek ?: return
        val (groupId, endsAt) = settings.activeManualFocusGroupId
        val active = groupId != null && endsAt > System.currentTimeMillis()
        val json = if (active) {
            val g = settings.manualFocusGroups.find { it.groupId == groupId }
            buildFocusJson(
                true, groupId!!, g?.groupName ?: "Focus", endsAt, System.currentTimeMillis(),
                g?.keywords?.toList() ?: emptyList(), g?.packages?.toList() ?: emptyList(),
                if (g?.blockMode == FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED) "all-except" else "only",
                g?.exitable ?: true,
            )
        } else {
            buildFocusJson(false, "", "", 0, 0, emptyList(), emptyList(), "only", true)
        }
        if (json == lastFocusJson) return
        lastFocusJson = json
        val aad = CryptoBox.recordAad(s.userId, NS_FOCUS, "active")
        val blob = CryptoBox.encryptRecord(d, aad, json)
        rest.upsertRecord(s, NS_FOCUS, "active", keys.deviceId, CryptoBox.toBase64Url(blob), System.currentTimeMillis())
    }

    private suspend fun applyFocusRow(d: ByteArray, s: SupabaseRest.Session, row: SupabaseRest.SyncRow) {
        val aad = CryptoBox.recordAad(s.userId, NS_FOCUS, row.recordKey)
        val json = CryptoBox.decryptRecord(d, aad, CryptoBox.fromBase64Url(row.ciphertext))
        val p = JSONObject(json)
        val active = p.optBoolean("active")
        val endsAt = p.optLong("endsAt")
        val groupId = p.optString("groupId")
        val domains = jsonArrToList(p.optJSONArray("domains"))
        val packages = jsonArrToList(p.optJSONArray("packages"))
        val mode = p.optString("mode", "only")
        val name = p.optString("name", "Focus")
        val exitable = p.optBoolean("exitable", true)

        // Remember this exact state so the settings observer does not push it back.
        lastFocusJson = buildFocusJson(active, groupId, name, endsAt, p.optLong("startedAt"), domains, packages, mode, exitable)

        if (active && endsAt > System.currentTimeMillis()) {
            dataStore.updateData { local ->
                val existing = local.manualFocusGroups.find { it.groupId == groupId }
                val groups = if (existing != null) {
                    local.manualFocusGroups
                } else {
                    injectedGroupIds.add(groupId)
                    local.manualFocusGroups + neth.iecal.curbox.data.models.ManualFocusGroup(
                        groupId = groupId,
                        groupName = name,
                        packages = HashSet(packages),
                        keywords = HashSet(domains),
                        blockMode = if (mode == "all-except") FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED else FocusBlockMode.BLOCK_SELECTED,
                        exitable = exitable,
                    )
                }
                local.copy(manualFocusGroups = groups, activeManualFocusGroupId = Pair(groupId, endsAt))
            }
        } else {
            dataStore.updateData { it.copy(activeManualFocusGroupId = Pair(null, 0L)) }
        }
        context.sendBroadcast(android.content.Intent(neth.iecal.curbox.blockers.FocusModeBlocker.INTENT_ACTION_REFRESH_FOCUS_MODE))
    }

    private fun jsonArrToList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // Focus group definitions (cross platform) -----------------------------

    // Canonical JSON for one focus group. Key order and sorted arrays match the
    // extension's canonicalFocusGroupJson so the same group reads as unchanged on
    // both sides. Apps and the DND flag are Android only but ride along so the
    // browser can hand them back untouched.
    private fun canonicalFocusGroupJson(g: neth.iecal.curbox.data.models.ManualFocusGroup): String {
        val o = JsonObject()
        o.addProperty("id", g.groupId)
        o.addProperty("name", g.groupName)
        o.addProperty("mode", if (g.blockMode == FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED) "all-except" else "only")
        o.addProperty("exitable", g.exitable)
        o.addProperty("autoTurnOnDnd", g.autoTurnOnDnd)
        o.add("domains", com.google.gson.JsonArray().apply { g.keywords.sorted().forEach { add(it) } })
        o.add("packages", com.google.gson.JsonArray().apply { g.packages.sorted().forEach { add(it) } })
        return o.toString()
    }

    private fun pushFocusGroups(settings: Settings) {
        val s = session ?: return
        val d = dek ?: return
        val present = HashSet<String>()
        for (g in settings.manualFocusGroups) {
            // A group injected only to satisfy a remote active focus is transient;
            // the device that actually owns it pushes the real definition.
            if (g.groupId in injectedGroupIds) continue
            present.add(g.groupId)
            val json = canonicalFocusGroupJson(g)
            if (focusGroupShadow[g.groupId] == json) continue
            val aad = CryptoBox.recordAad(s.userId, NS_FOCUS_GROUPS, g.groupId)
            val blob = CryptoBox.encryptRecord(d, aad, json)
            rest.upsertRecord(s, NS_FOCUS_GROUPS, g.groupId, keys.deviceId, CryptoBox.toBase64Url(blob), System.currentTimeMillis())
            focusGroupShadow[g.groupId] = json
        }
        // Tombstone groups we synced before but the user has since removed.
        for (id in focusGroupShadow.keys.toList()) {
            if (id in present) continue
            val aad = CryptoBox.recordAad(s.userId, NS_FOCUS_GROUPS, id)
            val blob = CryptoBox.encryptRecord(d, aad, JSONObject().put("id", id).toString())
            rest.upsertRecord(s, NS_FOCUS_GROUPS, id, keys.deviceId, CryptoBox.toBase64Url(blob), System.currentTimeMillis(), deleted = true)
            focusGroupShadow.remove(id)
        }
    }

    private suspend fun applyFocusGroupRows(d: ByteArray, s: SupabaseRest.Session, rows: List<SupabaseRest.SyncRow>) {
        if (rows.isEmpty()) return
        val removed = HashSet<String>()
        val upserts = LinkedHashMap<String, neth.iecal.curbox.data.models.ManualFocusGroup>()
        for (row in rows) {
            if (row.deleted) {
                removed.add(row.recordKey)
                upserts.remove(row.recordKey)
                focusGroupShadow.remove(row.recordKey)
                continue
            }
            val aad = CryptoBox.recordAad(s.userId, NS_FOCUS_GROUPS, row.recordKey)
            val json = CryptoBox.decryptRecord(d, aad, CryptoBox.fromBase64Url(row.ciphertext))
            val p = JSONObject(json)
            val g = neth.iecal.curbox.data.models.ManualFocusGroup(
                groupId = p.optString("id", row.recordKey),
                groupName = p.optString("name", "Focus"),
                packages = HashSet(jsonArrToList(p.optJSONArray("packages"))),
                keywords = HashSet(jsonArrToList(p.optJSONArray("domains"))),
                blockMode = if (p.optString("mode", "only") == "all-except") FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED else FocusBlockMode.BLOCK_SELECTED,
                exitable = p.optBoolean("exitable", true),
                autoTurnOnDnd = p.optBoolean("autoTurnOnDnd", false),
            )
            upserts[row.recordKey] = g
            removed.remove(row.recordKey)
            focusGroupShadow[row.recordKey] = canonicalFocusGroupJson(g)
        }
        dataStore.updateData { local ->
            val byId = LinkedHashMap<String, neth.iecal.curbox.data.models.ManualFocusGroup>()
            for (g in local.manualFocusGroups) byId[g.groupId] = g
            for (id in removed) byId.remove(id)
            for ((id, g) in upserts) byId[id] = g
            // These are now real shared groups, not transient injections.
            injectedGroupIds.removeAll(upserts.keys)
            local.copy(manualFocusGroups = byId.values.toList())
        }
    }

    // Pull -----------------------------------------------------------------

    private suspend fun pullSinceCursor() {
        val s = session ?: return
        val d = dek ?: return
        try {
            val rows = rest.pull(s, keys.cursor)
            // Idle poll: nothing new. Skip the work and, importantly, do not
            // republish status, so the UI does not re-render every poll tick.
            if (rows.isEmpty()) return
            var configRow: SupabaseRest.SyncRow? = null
            var focusRow: SupabaseRest.SyncRow? = null
            val focusGroupRows = ArrayList<SupabaseRest.SyncRow>()
            // Loaded lazily: most pulls carry no usage rows, so we avoid reading
            // and parsing the whole remote usage file on every tick.
            var remoteUsage: RemoteUsageStore? = null
            // Track the high water mark separately and only commit it once the
            // whole batch is applied. A single undecryptable row is skipped rather
            // than poisoning the batch, but the cursor never jumps past work we
            // have not finished, so a mid pull failure simply retries next time.
            var maxCursor = keys.cursor
            for (row in rows) {
                runCatching {
                    when {
                        row.namespace == NS_ANDROID_CONFIG && row.deviceId != keys.deviceId -> configRow = row
                        row.namespace == NS_FOCUS && row.deviceId != keys.deviceId -> focusRow = row
                        row.namespace == NS_FOCUS_GROUPS && row.deviceId != keys.deviceId -> focusGroupRows.add(row)
                        row.namespace == NS_USAGE_WEB && row.deviceId != keys.deviceId ->
                            applyUsageRow(d, s, row, remoteUsage ?: RemoteUsageStore(context).also { remoteUsage = it }, web = true)
                        row.namespace == NS_USAGE_APP && row.deviceId != keys.deviceId ->
                            applyUsageRow(d, s, row, remoteUsage ?: RemoteUsageStore(context).also { remoteUsage = it }, web = false)
                        else -> {}
                    }
                }
                if (row.updatedAt > maxCursor) maxCursor = row.updatedAt
            }
            remoteUsage?.flush()
            if (focusGroupRows.isNotEmpty()) runCatching { applyFocusGroupRows(d, s, focusGroupRows) }
            configRow?.let { runCatching { applyConfigRow(d, s, it) } }
            focusRow?.let { runCatching { applyFocusRow(d, s, it) } }
            keys.cursor = maxCursor
            publishStatus(lastSync = System.currentTimeMillis())
        } catch (e: Exception) {
            publishStatus(error = e.message)
        }
    }

    private suspend fun applyConfigRow(d: ByteArray, s: SupabaseRest.Session, row: SupabaseRest.SyncRow) {
        val aad = CryptoBox.recordAad(s.userId, NS_ANDROID_CONFIG, row.recordKey)
        val json = CryptoBox.decryptRecord(d, aad, CryptoBox.fromBase64Url(row.ciphertext))
        val remote = gson.fromJson(json, Settings::class.java)
        val norm = gson.toJson(normalize(remote))
        if (norm == lastConfigJson) return
        lastConfigJson = norm
        dataStore.updateData { local ->
            // The config payload no longer carries focus groups or the active
            // focus, so keep the local copies of those.
            remote.copy(
                activeManualFocusGroupId = local.activeManualFocusGroupId,
                nextWebsiteRecheckTime = local.nextWebsiteRecheckTime,
                manualFocusGroups = local.manualFocusGroups,
            )
        }
    }

    private fun applyUsageRow(d: ByteArray, s: SupabaseRest.Session, row: SupabaseRest.SyncRow, store: RemoteUsageStore, web: Boolean) {
        val ns = if (web) NS_USAGE_WEB else NS_USAGE_APP
        val aad = CryptoBox.recordAad(s.userId, ns, row.recordKey)
        val json = CryptoBox.decryptRecord(d, aad, CryptoBox.fromBase64Url(row.ciphertext))
        store.put(ns, row.recordKey, json)
    }

    // Status ---------------------------------------------------------------

    private fun publishStatus(lastSync: Long? = _status.value.lastSync, error: String? = null) {
        val s = session
        _status.value = SyncStatus(
            signedIn = s != null,
            email = s?.email,
            hasVault = vaultExists || dek != null,
            unlocked = dek != null,
            deviceId = keys.deviceId,
            lastSync = lastSync,
            error = error,
            pendingEmail = pendingEmail,
        )
    }

    // Room stores dates in the app's native "dd MMMM yyyy" format; the sync wire
    // format is ISO yyyy-MM-dd so it lines up with the extension cross platform.
    private fun todayNative(): String = neth.iecal.curbox.utils.TimeTools.getCurrentDate()
    private fun todayIso(): String = java.time.LocalDate.now().toString()

    // Converts the app's native "dd MMMM yyyy" date back to the ISO date used on
    // the wire, so a record observed for a given day always carries that day's
    // ISO key even right after a midnight rollover. Falls back to today if a
    // non default locale makes the month name unparseable.
    private fun isoFor(native: String): String = try {
        java.time.LocalDate.parse(
            native,
            java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault()),
        ).toString()
    } catch (e: Exception) {
        todayIso()
    }
}
