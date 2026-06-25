package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import neth.iecal.curbox.utils.ViewUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.TimeTools
import java.util.Calendar
import kotlin.math.max

class AppBlockerGroupsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "app_blocker_groups"
    }

    private lateinit var rvGroups: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var fabAddGroup: FloatingActionButton

    private val viewModel: AppBlockerSettingViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_app_blocker_groups, container, false)
        
        rvGroups = view.findViewById(R.id.rv_app_groups)
        tvEmptyState = view.findViewById(R.id.tv_empty_state)
        fabAddGroup = view.findViewById(R.id.fab_add_group)

        view.findViewById<Button>(R.id.btn_help).setOnClickListener {
            ViewUtils.showHelpPopup(it, "Block distracting apps and regain control over your screen time.", "https://curbox.app/docs/reducers/app-pause/")
        }


        fabAddGroup.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", CreateAppGroupFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        rvGroups.layoutManager = LinearLayoutManager(requireContext())
        
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.groups.collectLatest { groups ->
                    val remainingUsageByGroup = calculateRemainingUsageMinutesByGroup(groups)
                    if (groups.isEmpty()) {
                        tvEmptyState.visibility = View.VISIBLE
                        rvGroups.visibility = View.GONE
                    } else {
                        tvEmptyState.visibility = View.GONE
                        rvGroups.visibility = View.VISIBLE
                        rvGroups.adapter = AppGroupAdapter(groups, remainingUsageByGroup)
                    }
                }
            }
        }
    }

    private suspend fun calculateRemainingUsageMinutesByGroup(groups: List<AppGroup>): Map<String, Long> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val usageGroups = groups.filter { it.blockingType == AppBlockingType.Usage }
            if (usageGroups.isEmpty()) return@withContext emptyMap()

            val today = TimeTools.getCurrentDate()
            val usageByPackage = AppDatabase.getInstance(requireContext())
                .appUsageDao()
                .getForDate(today)
                .associate { it.packageName to it.totalTime }
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
                val groupUsageMillis = group.selectedPackages.sumOf { pkg ->
                    usageByPackage[pkg.trim()] ?: 0L
                }
                val remainingMillis = max(0L, limitMillis - groupUsageMillis)
                val remainingMinutes = if (remainingMillis == 0L) 0L else (remainingMillis + 59_999L) / 60_000L
                group.id to remainingMinutes
            }
        }
    }

    inner class AppGroupAdapter(
        private val groupList: List<AppGroup>,
        private val remainingUsageByGroup: Map<String, Long>
    ) :
        RecyclerView.Adapter<AppGroupAdapter.ViewHolder>() {

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
            
            val typeText = if (group.blockingType == AppBlockingType.Timed) "Time Based" else "Usage Based"
            val remainingText = if (group.blockingType == AppBlockingType.Usage) {
                " • Remaining today: ${remainingUsageByGroup[group.id] ?: 0L} mins"
            } else {
                ""
            }
            holder.tvDetails.text = "${group.selectedPackages.size} Apps • $typeText$remainingText"
            
            holder.switchActive.setOnCheckedChangeListener(null)
            holder.switchActive.isChecked = group.isActive
            
            holder.switchActive.setOnCheckedChangeListener { _, isChecked ->
                viewModel.updateGroupActiveState(position, isChecked)
            }
            
            holder.itemView.setOnClickListener {
                val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                    putExtra("fragment", CreateAppGroupFragment.FRAGMENT_ID)
                    putExtra("group_id", group.id)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount() = groupList.size
    }
}
