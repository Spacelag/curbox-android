package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.gson.Gson
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.databinding.FragmentKeywordBlockerBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.TimeTools
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

class KeywordBlockerFragment : Fragment() {

    private var _binding: FragmentKeywordBlockerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: KeywordBlockerViewModel by activityViewModels()
    private var isUpdatingUi = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeywordBlockerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!viewModel.keywordBlockerConfig.value.isActive) {
            viewModel.setIsActive(true)
        }
        binding.rvKeywordGroups.layoutManager = LinearLayoutManager(requireContext())
        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.btnMenu.setOnClickListener { view ->
            showPopupMenu(view)
        }

        binding.fabAddGroup.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", CreateKeywordGroupFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }
    }

    private fun showPopupMenu(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.menu_keyword_blocker, popup.menu)

        val config = viewModel.keywordBlockerConfig.value
        popup.menu.findItem(R.id.menu_block_unsupported_browsers).isChecked = config.blockAllExceptSupported

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_block_unsupported_browsers -> {
                    val newValue = !item.isChecked
                    item.isChecked = newValue
                    viewModel.setBlockAllExceptSupported(newValue)
                    true
                }
                R.id.menu_help -> {
                    val url = "https://curbox.app/docs/reducers/keyword-blocker/"
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.keywordBlockerConfig.collectLatest { config ->
                isUpdatingUi = true
                val remainingUsageByGroup = calculateRemainingUsageMinutesByGroup(config.keywordGroups)

                if (config.keywordGroups.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.rvKeywordGroups.visibility = View.GONE
                } else {
                    binding.tvEmptyState.visibility = View.GONE
                    binding.rvKeywordGroups.visibility = View.VISIBLE
                    binding.rvKeywordGroups.adapter = KeywordGroupAdapter(config.keywordGroups, remainingUsageByGroup)
                }
                isUpdatingUi = false
            }
        }
    }

    private suspend fun calculateRemainingUsageMinutesByGroup(groups: List<KeywordGroup>): Map<String, Long> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val usageGroups = groups.filter { it.blockingType == AppBlockingType.Usage }
            if (usageGroups.isEmpty()) return@withContext emptyMap()

            val today = TimeTools.getCurrentDate()
            val websiteStats = AppDatabase.getInstance(requireContext()).websiteStatsDao().getStatsForDate(today)
            val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1

            usageGroups.associate { group ->
                val config = runCatching {
                    Gson().fromJson(group.setting, AppUsageConfig::class.java)
                }.getOrNull()
                val limitMinutes = if (config == null) 0L else if (config.isDailyUniform) {
                    config.uniformLimit
                } else {
                    config.dailyLimits[dayOfWeek]
                }
                val limitMillis = limitMinutes * 60_000L
                val patterns = compileKeywords(group.selectedKeywords)
                val usageMillis = websiteStats
                    .filter { matchesPatterns(patterns, it.urlIdentifier) }
                    .sumOf { it.totalTime }
                val remainingMillis = max(0L, limitMillis - usageMillis)
                val remainingMinutes = if (remainingMillis == 0L) 0L else (remainingMillis + 59_999L) / 60_000L
                group.id to remainingMinutes
            }
        }
    }

    private fun compileKeywords(keywords: Collection<String>): Pair<List<Regex>, List<String>> {
        val regexes = mutableListOf<Regex>()
        val literals = mutableListOf<String>()
        for (kw in keywords) {
            val lower = kw.lowercase(Locale.ROOT)
            when {
                lower.startsWith("r:") ->
                    runCatching { Regex(lower.removePrefix("r:")) }.getOrNull()?.let { regexes.add(it) }
                lower.contains('*') || lower.contains('?') ->
                    regexes.add(wildcardToRegex(lower))
                else -> literals.add(lower)
            }
        }
        return regexes to literals
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val escaped = pattern
            .replace(Regex("""[.+^$()|\[\]{}\\]"""), """\\$0""")
            .replace("?", ".")
            .replace("*", ".*")
        val prefix = if (!pattern.startsWith("http") && !pattern.startsWith("*") &&
            !pattern.startsWith("/") && !pattern.startsWith("?")
        ) {
            """(?:https?://)?(?:www\.)?"""
        } else ""
        return Regex(prefix + escaped)
    }

    private fun matchesLiteral(keyword: String, urlIdentifier: String): Boolean {
        val url = urlIdentifier.lowercase(Locale.ROOT)
        val urlNoWww = url.removePrefix("www.")
        val kwNoWww = keyword.removePrefix("www.")

        if (url == keyword || urlNoWww == kwNoWww) return true

        if (url.startsWith("$keyword/") || url.startsWith("$keyword?") ||
            urlNoWww.startsWith("$kwNoWww/") || urlNoWww.startsWith("$kwNoWww?")
        ) return true

        if (keyword.startsWith("/") && url.contains(keyword)) return true

        if (!keyword.contains('.') && !keyword.contains('/')) {
            val domain = url.substringBefore('/')
            if (domain.split('.').any { it == keyword }) return true
        }

        return false
    }

    private fun matchesPatterns(patterns: Pair<List<Regex>, List<String>>, urlIdentifier: String): Boolean {
        val lower = urlIdentifier.lowercase(Locale.ROOT)
        val (regexes, literals) = patterns
        return regexes.any { it.containsMatchIn(lower) } || literals.any { matchesLiteral(it, urlIdentifier) }
    }

    inner class KeywordGroupAdapter(
        private val groupList: List<KeywordGroup>,
        private val remainingUsageByGroup: Map<String, Long>
    ) :
        RecyclerView.Adapter<KeywordGroupAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_group_name)
            val tvDetails: TextView = view.findViewById(R.id.tv_group_details)
            val switchActive: SwitchMaterial = view.findViewById(R.id.switch_group_active)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_group, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = groupList[position]
            holder.tvName.text = group.name
            val typeText = if (group.blockingType == AppBlockingType.Usage) "Usage Based" else "Time Based"
            val remainingText = if (group.blockingType == AppBlockingType.Usage) {
                " • Remaining today: ${remainingUsageByGroup[group.id] ?: 0L} mins"
            } else {
                ""
            }
            holder.tvDetails.text = "${group.selectedKeywords.size} Keywords • $typeText$remainingText"
            
            holder.switchActive.setOnCheckedChangeListener(null)
            holder.switchActive.isChecked = group.isActive
            holder.switchActive.setOnCheckedChangeListener { _, isChecked ->
                viewModel.updateGroupActiveState(group.id, isChecked)
            }
            
            holder.itemView.setOnClickListener {
                val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                    putExtra("fragment", CreateKeywordGroupFragment.FRAGMENT_ID)
                    putExtra("group_id", group.id)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount() = groupList.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "keyword_blocker"
    }
}
