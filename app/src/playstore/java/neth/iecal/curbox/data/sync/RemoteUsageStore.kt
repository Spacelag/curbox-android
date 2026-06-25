package neth.iecal.curbox.data.sync

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * Holds other devices' usage that we pulled down, kept separate from local Room
 * so this device's own counters stay authoritative. The UI can union these in to
 * show unified totals across every device. One JSON file, one record per other
 * device per day, never touching the local usage database.
 *
 * The file is v2: each record is now a whole day for a device (record_key
 * deviceId:date) instead of one row per domain. The old v1 file is deleted on
 * first use so the two formats can never be summed together and double count.
 */
class RemoteUsageStore(context: Context) {
    private val file = File(context.filesDir, "sync_remote_usage_v2.json")
    private val records: HashMap<String, String> = load(context)
    private var dirty = false

    private fun load(context: Context): HashMap<String, String> {
        // One time migration: drop the per domain v1 cache so old and new records
        // are never mixed.
        runCatching {
            val legacy = File(context.filesDir, "sync_remote_usage.json")
            if (legacy.exists()) legacy.delete()
        }
        if (!file.exists()) return HashMap()
        val loaded = try {
            val obj = JSONObject(file.readText())
            HashMap<String, String>().apply { obj.keys().forEach { put(it, obj.getString(it)) } }
        } catch (_: Exception) {
            HashMap()
        }
        pruneOld(loaded)
        return loaded
    }

    // Keep only the recent days the UI can actually show, so the file cannot grow
    // without bound as the days go by.
    private fun pruneOld(map: HashMap<String, String>) {
        val cutoff = java.time.LocalDate.now().minusDays(14).toString()
        val stale = map.entries.filter { (_, json) ->
            runCatching { JSONObject(json).optString("date") < cutoff }.getOrDefault(false)
        }.map { it.key }
        if (stale.isNotEmpty()) {
            stale.forEach { map.remove(it) }
            dirty = true
        }
    }

    fun put(namespace: String, recordKey: String, payloadJson: String) {
        records["$namespace/$recordKey"] = payloadJson
        dirty = true
    }

    fun flush() {
        if (!dirty) return
        val obj = JSONObject()
        records.forEach { (k, v) -> obj.put(k, v) }
        file.writeText(obj.toString())
        dirty = false
    }

    /** Drops every other device's cached usage. Used on sign out so a signed out
     *  device never keeps showing data that belonged to a different account. */
    fun clear() {
        records.clear()
        dirty = false
        runCatching { if (file.exists()) file.delete() }
    }

    /** Summed app milliseconds by package for a date, across all other Android devices. */
    fun appTotals(date: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((key, json) in records) {
            if (!key.startsWith("usage_app/")) continue
            val o = JSONObject(json)
            if (o.optString("date") != date) continue
            val apps = o.optJSONObject("apps") ?: continue
            val keys = apps.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val ms = apps.optJSONObject(pkg)?.optLong("ms") ?: 0L
                out[pkg] = (out[pkg] ?: 0L) + ms
            }
        }
        return out
    }

    /** Summed website milliseconds by domain for a date, across all other devices. */
    fun websiteTotals(date: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((key, json) in records) {
            if (!key.startsWith("usage_web/")) continue
            val o = JSONObject(json)
            if (o.optString("date") != date) continue
            val domains = o.optJSONObject("domains") ?: continue
            val keys = domains.keys()
            while (keys.hasNext()) {
                val domain = keys.next()
                val ms = domains.optJSONObject(domain)?.optLong("ms") ?: 0L
                out[domain] = (out[domain] ?: 0L) + ms
            }
        }
        return out
    }
}
