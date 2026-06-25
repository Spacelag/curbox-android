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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.AppUsageEntity
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.DataStoreManager
import org.json.JSONObject

@OptIn(FlowPreview::class)
class PlaystoreSyncProvider(private val context: Context) : SyncProvider {

    private val gson = Gson()
    private val rest by lazy { SupabaseRest() }
    private val keys by lazy { SecureKeyStore(context) }
    private val db by lazy { AppDatabase.getInstance(context) }
    private val dataStore by lazy { DataStoreManager.getSettingsDataStore(context, gson) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = kotlinx.coroutines.sync.Mutex()

    private val NS_ANDROID_CONFIG = "android_config"
    private val NS_USAGE_WEB = "usage_web"
    private val NS_USAGE_APP = "usage_app"
    private val NS_FOCUS = "focus_state"
    private val NS_FOCUS_GROUPS = "focus_groups"

    // Websites visited for less than this in a day aren't worth syncing.
    private val MIN_WEBSITE_SYNC_MS = 60_000L

    private var session: SupabaseRest.Session? = null
    private var dek: ByteArray? = null
    private var vaultExists: Boolean = false
    private var realtime: RealtimeClient? = null
    @Volatile private var realtimeConnected = false
    private var pollStarted = false
    private var lastConfigJson: String? = null
    private var lastFocusJson: String? = null
    private val injectedGroupIds = HashSet<String>()
    private val focusGroupShadow = HashMap<String, String>()
    private val pushedHashes = HashMap<String, Int>()
    private var observersStarted = false

    private val _status = MutableStateFlow(SyncStatus())
    override val status: StateFlow<SyncStatus> = _status
    override val isAvailable = true

    override fun start() {
        scope.launch { ensureStarted() }
    }

    private suspend fun ensureStarted() = startMutex.withLock {
        if (session != null) return
        runCatching { SyncWorker.schedule(context) }
        val refresh = keys.refreshToken ?: return
        try {
            session = rest.refresh(refresh).also { persistSession(it) }
            keys.dekB64?.let { dek = CryptoBox.fromBase64Url(it) }
            onSignedIn()
        } catch (e: Exception) {
            if (isRejectedToken(e)) {
                keys.clear()
                session = null
                dek = null
            }
            publishStatus(error = e.message)
        }
    }

    fun wake() {
        scope.launch {
            ensureStarted()
            if (session != null && dek != null) {
                ensureFreshToken()
                runCatching { pullSinceCursor() }
                runCatching { pushUsage() }
            }
        }
    }

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
        runCatching { session?.let { rest.clearDeviceToken(it, keys.deviceId) } }
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
        runCatching { RemoteUsageStore(context).clear() }
        publishStatus()
    }

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

    private fun requireSession(): SupabaseRest.Session = session ?: throw IllegalStateException("sign in first")

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
            rest.upsertDevice(s, keys.deviceId, "android", "android", keys.fcmToken)
        } catch (_: Exception) {
        }
        registerFcmToken()
        vaultExists = if (dek != null) true else runCatching { rest.getVault(s) != null }.getOrElse { vaultExists }
        publishStatus()
        if (dek != null) {
            startObservers()
            if (!neth.iecal.curbox.BuildConfig.SYNC_USE_FCM) startRealtime()
            startSafetyPoll()
            pullSinceCursor()
            pushConfig()
            runCatching { pushFocusGroups(dataStore.data.first()) }
            pushUsage()
            runCatching { pushFocusFrom(dataStore.data.first()) }
        }
        publishStatus()
    }

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

    fun onFcmToken(token: String) {
        keys.fcmToken = token
        scope.launch {
            val s = session ?: return@launch
            runCatching { rest.upsertDevice(s, keys.deviceId, "android", "android", token) }
        }
    }

    private fun registerFcmToken() {
        scope.launch {
            val token = runCatching { FcmPush.token(context) }.getOrNull() ?: return@launch
            keys.fcmToken = token
            val s = session ?: return@launch
            runCatching { rest.upsertDevice(s, keys.deviceId, "android", "android", token) }
        }
    }

    private fun startSafetyPoll() {
        if (pollStarted) return
        pollStarted = true
        scope.launch {
            while (true) {
                val interval = when {
                    neth.iecal.curbox.BuildConfig.SYNC_USE_FCM -> 300_000L
                    realtimeConnected -> 60_000L
                    else -> 10_000L
                }
                delay(interval)
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
        scope.launch {
            dataStore.data
                .distinctUntilChangedBy { it.activeManualFocusGroupId }
                .collect { settings ->
                    if (dek != null) runCatching { pushFocusFrom(settings) }
                }
        }
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

    private fun normalize(s: Settings): Settings =
        s.copy(
            activeManualFocusGroupId = Pair(null, 0L),
            nextWebsiteRecheckTime = 0L,
            manualFocusGroups = emptyList(),
        )

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

    private fun pushWebRows(date: String, rows: List<WebsiteStatsEntity>) {
        val s = session ?: return
        val d = dek ?: return
        val domains = JsonObject()
        for ((domain, group) in rows.groupBy { it.domain }) {
            // Skip brief visits. Anything under a minute is noise we don't sync.
            val total = group.sumOf { it.totalTime }
            if (total < MIN_WEBSITE_SYNC_MS) continue
            val paths = JsonObject().apply { group.forEach { addProperty(it.urlIdentifier, it.totalTime) } }
            domains.add(domain, JsonObject().apply {
                addProperty("ms", total)
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
            if (g.groupId in injectedGroupIds) continue
            present.add(g.groupId)
            val json = canonicalFocusGroupJson(g)
            if (focusGroupShadow[g.groupId] == json) continue
            val aad = CryptoBox.recordAad(s.userId, NS_FOCUS_GROUPS, g.groupId)
            val blob = CryptoBox.encryptRecord(d, aad, json)
            rest.upsertRecord(s, NS_FOCUS_GROUPS, g.groupId, keys.deviceId, CryptoBox.toBase64Url(blob), System.currentTimeMillis())
            focusGroupShadow[g.groupId] = json
        }
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
            injectedGroupIds.removeAll(upserts.keys)
            local.copy(manualFocusGroups = byId.values.toList())
        }
    }

    private suspend fun pullSinceCursor() {
        val s = session ?: return
        val d = dek ?: return
        try {
            val rows = rest.pull(s, keys.cursor)
            if (rows.isEmpty()) return
            var configRow: SupabaseRest.SyncRow? = null
            var focusRow: SupabaseRest.SyncRow? = null
            val focusGroupRows = ArrayList<SupabaseRest.SyncRow>()
            var remoteUsage: RemoteUsageStore? = null
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

    private fun todayNative(): String = neth.iecal.curbox.utils.TimeTools.getCurrentDate()
    private fun todayIso(): String = java.time.LocalDate.now().toString()

    private fun isoFor(native: String): String = try {
        java.time.LocalDate.parse(
            native,
            java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault()),
        ).toString()
    } catch (e: Exception) {
        todayIso()
    }
}
