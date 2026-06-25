package neth.iecal.curbox.ui.fragments.main.usage

import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.ui.views.WeeklyBarGraphView
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.getDefaultLauncherPackageName
import java.util.concurrent.ConcurrentHashMap
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.utils.DataStoreManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AllAppsUsageViewModel(application: Application) : AndroidViewModel(application) {

    private val usageStatsHelper = UsageStatsHelper(application)
    private val packageManager = application.packageManager
    private val websiteStatsDao = AppDatabase.getInstance(application).websiteStatsDao()

    // Search keywords typed in the URL bar get stored with the raw text as the domain.
    // A real website domain has no spaces and contains at least one dot (e.g. "youtube.com").
    private val domainRegex = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$", RegexOption.IGNORE_CASE)

    private fun WebsiteStatsEntity.isWebsite(): Boolean =
        domain.isNotBlank() && !domain.contains(' ') && domainRegex.matches(domain)

    val ignoredPackages: MutableSet<String> = mutableSetOf()

    private val dayStatsCache = ConcurrentHashMap<LocalDate, List<AllAppsUsageFragment.Stat>>()
    private val appMetadataCache = ConcurrentHashMap<String, AppMetadata>()

    data class AppMetadata(
        val label: CharSequence,
        val category: String,
        val isSystemApp: Boolean,
        val installDate: String,
        val lastUpdate: String,
        val icon: Drawable?
    )

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // Current week offset: 0 = current week, -1 = last week, etc.
    private val _weekOffset = MutableLiveData(0)
    val weekOffset: LiveData<Int> = _weekOffset

    // Week range label like "Mar 10 – Mar 16"
    private val _weekRangeLabel = MutableLiveData<String>()
    val weekRangeLabel: LiveData<String> = _weekRangeLabel

    // Weekly bar data (7 entries)
    private val _weeklyData = MutableLiveData<List<WeeklyBarGraphView.DayData>>()
    val weeklyData: LiveData<List<WeeklyBarGraphView.DayData>> = _weeklyData

    // Selected day index within the week (0-6)
    private val _selectedDayIndex = MutableLiveData(6) // default to last day (Sunday) or today
    val selectedDayIndex: LiveData<Int> = _selectedDayIndex

    // Stats for the selected day
    private val _selectedDayStats = MutableLiveData<List<AllAppsUsageFragment.Stat>>()
    val selectedDayStats: LiveData<List<AllAppsUsageFragment.Stat>> = _selectedDayStats

    private val _selectedDayWebsiteStats = MutableLiveData<List<WebsiteStatsEntity>>()
    val selectedDayWebsiteStats: LiveData<List<WebsiteStatsEntity>> = _selectedDayWebsiteStats

    // Total usage time in millis for selected day
    private val _totalTime = MutableLiveData<Long>(0L)
    val totalTime: LiveData<Long> = _totalTime

    // Date sublabel ("TOTAL TODAY" or "TOTAL · Mar 15")
    private val _dateSublabel = MutableLiveData("TOTAL TODAY")
    val dateSublabel: LiveData<String> = _dateSublabel

    // Can navigate forward?
    private val _canGoNext = MutableLiveData(false)
    val canGoNext: LiveData<Boolean> = _canGoNext

    private val dayLabelFormatter = DateTimeFormatter.ofPattern("MMM d")

    fun initialize() {
        viewModelScope.launch(Dispatchers.IO) {
            getDefaultLauncherPackageName(getApplication<Application>().packageManager)?.let {
                ignoredPackages.add(it)
            }
            val datastore = DataStoreManager(getApplication())
            ignoredPackages.addAll(datastore.settings.first().usageTrackerIgnoredApps)
            loadWeekData()
        }
    }

    fun goToPreviousWeek() {
        _weekOffset.value = (_weekOffset.value ?: 0) - 1
        viewModelScope.launch(Dispatchers.IO) {
            loadWeekData()
        }
    }

    fun goToNextWeek() {
        val current = _weekOffset.value ?: 0
        if (current < 0) {
            _weekOffset.value = current + 1
            viewModelScope.launch(Dispatchers.IO) {
                loadWeekData()
            }
        }
    }

    fun selectDay(index: Int) {
        _selectedDayIndex.value = index
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _isLoading.value = true }
            val weekStart = getWeekStart(_weekOffset.value ?: 0)
            val selectedDate = weekStart.plusDays(index.toLong())
            loadDayStats(selectedDate)
            withContext(Dispatchers.Main) { _isLoading.value = false }
        }
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            loadWeekData()
        }
    }

    private suspend fun loadWeekData() {
        withContext(Dispatchers.Main) { _isLoading.value = true }

        val offset = withContext(Dispatchers.Main) { _weekOffset.value ?: 0 }
        val weekStart = getWeekStart(offset)
        val weekEnd = weekStart.plusDays(6)

        val today = LocalDate.now()
        val isCurrentWeek = offset == 0

        withContext(Dispatchers.Main) {
            _canGoNext.value = offset < 0

            val startLabel = weekStart.format(dayLabelFormatter)
            val endLabel = weekEnd.format(dayLabelFormatter)
            _weekRangeLabel.value = "$startLabel – $endLabel"
        }

        val dayDataList = mutableListOf<WeeklyBarGraphView.DayData>()
        val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")

        var todayIndex = -1

        for (i in 0..6) {
            val date = weekStart.plusDays(i.toLong())
            val isFuture = date.isAfter(today)

            val totalTimeMs = if (isFuture) {
                0L
            } else {
                getFilteredStatsForDay(date).sumOf { it.totalTime }
            }

            val hours = totalTimeMs / (1000f * 60f * 60f)
            val dateMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            dayDataList.add(WeeklyBarGraphView.DayData(dayLabels[i], hours, dateMillis))

            if (date == today) todayIndex = i
        }

        // Choose the selected day: today if in this week, else last day of the week
        val defaultSelected = if (isCurrentWeek && todayIndex >= 0) todayIndex else 6

        withContext(Dispatchers.Main) {
            _weeklyData.value = dayDataList
            _selectedDayIndex.value = defaultSelected
        }

        // Load stats for the selected day
        val selectedDate = weekStart.plusDays(defaultSelected.toLong())
        loadDayStats(selectedDate)

        withContext(Dispatchers.Main) { _isLoading.value = false }
    }

    private suspend fun loadDayStats(date: LocalDate) {
        val stats = getFilteredStatsForDay(date)

        // Fold in app usage synced from the user's other Android devices, summed
        // per app so each row shows combined time across every device. Empty on
        // F-Droid and when nothing has synced.
        val remoteApps = runCatching {
            neth.iecal.curbox.data.sync.SyncGateway.provider.remoteAppUsage(date.toString())
        }.getOrDefault(emptyMap())
        val appStats = if (remoteApps.isEmpty()) stats else mergeRemoteApps(stats, remoteApps)

        preloadAppMetadata(appStats.map { it.packageName })
        val total = appStats.sumOf { it.totalTime }
        val today = LocalDate.now()
        val isToday = date == today

        val sublabel = if (isToday) {
            "TOTAL TODAY"
        } else {
            "TOTAL · ${date.format(dayLabelFormatter)}"
        }

        val dateString = date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault()))
        val websiteStats = websiteStatsDao.getStatsForDate(dateString).filter { it.isWebsite() }

        // Fold in website usage synced from other devices (e.g. the browser
        // extension) as a single "Synced browsing" row. Empty on F-Droid.
        val remote = runCatching {
            neth.iecal.curbox.data.sync.SyncGateway.provider.remoteWebsiteUsage(date.toString())
        }.getOrDefault(emptyMap())

        var statsOut = appStats.sortedByDescending { it.totalTime }
        var websiteOut = websiteStats
        if (remote.isNotEmpty()) {
            val syncedWebsites = remote.map { (domain, ms) ->
                WebsiteStatsEntity(
                    date = dateString,
                    packageName = neth.iecal.curbox.data.sync.SYNCED_WEB_PACKAGE,
                    urlIdentifier = domain,
                    domain = domain,
                    totalTime = ms,
                    lastVisited = 0L,
                )
            }
            websiteOut = websiteStats + syncedWebsites
            statsOut = (appStats + AllAppsUsageFragment.Stat(
                neth.iecal.curbox.data.sync.SYNCED_WEB_PACKAGE,
                remote.values.sum(),
            )).sortedByDescending { it.totalTime }
        }

        withContext(Dispatchers.Main) {
            _selectedDayStats.value = statsOut
            _selectedDayWebsiteStats.value = websiteOut
            _totalTime.value = total
            _dateSublabel.value = sublabel
        }
    }

    // Combines other devices' per app time into this device's list: matching apps
    // get their time added together, and apps that only ran on another device are
    // appended as their own rows.
    private fun mergeRemoteApps(
        local: List<AllAppsUsageFragment.Stat>,
        remote: Map<String, Long>,
    ): List<AllAppsUsageFragment.Stat> {
        val localByPkg = local.associateBy { it.packageName }
        val merged = ArrayList<AllAppsUsageFragment.Stat>(local.size + remote.size)
        for (st in local) {
            val extra = remote[st.packageName] ?: 0L
            merged.add(
                if (extra > 0L) {
                    AllAppsUsageFragment.Stat(st.packageName, st.totalTime + extra, st.sessions, st.hourlyUsage)
                } else {
                    st
                },
            )
        }
        for ((pkg, ms) in remote) {
            if (pkg !in localByPkg && ms >= 1_000 && pkg !in ignoredPackages) {
                merged.add(AllAppsUsageFragment.Stat(pkg, ms))
            }
        }
        return merged
    }

    private fun getWeekStart(offset: Int): LocalDate {
        return LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(offset.toLong())
    }

    private suspend fun getStatsForDay(date: LocalDate): List<AllAppsUsageFragment.Stat> {
        dayStatsCache[date]?.let { return it }
        val stats = usageStatsHelper.getForegroundStatsByDay(date)
        dayStatsCache[date] = stats
        return stats
    }

    private suspend fun getFilteredStatsForDay(date: LocalDate): List<AllAppsUsageFragment.Stat> {
        return getStatsForDay(date).filter {
            it.totalTime >= 1_000 && it.packageName !in ignoredPackages
        }
    }

    private suspend fun preloadAppMetadata(packageNames: Collection<String>) {
        withContext(Dispatchers.IO) {
            packageNames.distinct().forEach { packageName ->
                getAppMetadata(packageName)
            }
        }
    }

    fun getAppMetadata(packageName: String): AppMetadata {
        if (packageName == neth.iecal.curbox.data.sync.SYNCED_WEB_PACKAGE) {
            return AppMetadata(
                label = "Synced browsing",
                category = "OTHER DEVICES",
                isSystemApp = false,
                installDate = "",
                lastUpdate = "",
                icon = androidx.core.content.ContextCompat.getDrawable(getApplication(), neth.iecal.curbox.R.drawable.ic_synced_web),
            )
        }
        return appMetadataCache.computeIfAbsent(packageName) {
            try {
                val appInfo = packageManager.getApplicationInfo(it, 0)
                val packageInfo = packageManager.getPackageInfo(it, 0)
                val category = when (appInfo.category) {
                    ApplicationInfo.CATEGORY_GAME -> "GAME"
                    ApplicationInfo.CATEGORY_SOCIAL -> "SOCIAL NETWORKING"
                    ApplicationInfo.CATEGORY_PRODUCTIVITY -> "PRODUCTIVITY"
                    ApplicationInfo.CATEGORY_VIDEO -> "VIDEO"
                    ApplicationInfo.CATEGORY_AUDIO -> "AUDIO"
                    ApplicationInfo.CATEGORY_NEWS -> "NEWS"
                    ApplicationInfo.CATEGORY_IMAGE -> "IMAGE"
                    ApplicationInfo.CATEGORY_MAPS -> "MAPS"
                    else -> "APP"
                }

                AppMetadata(
                    label = appInfo.loadLabel(packageManager),
                    category = category,
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    installDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        .format(Date(packageInfo.firstInstallTime)),
                    lastUpdate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        .format(Date(packageInfo.lastUpdateTime)),
                    icon = appInfo.loadIcon(packageManager)
                )
            } catch (e: Exception) {
                AppMetadata(
                    label = packageName,
                    category = "APP",
                    isSystemApp = false,
                    installDate = "N/A",
                    lastUpdate = "N/A",
                    icon = null
                )
            }
        }
    }

    fun getAppCategory(packageName: String): String {
        return getAppMetadata(packageName).category
    }
}
