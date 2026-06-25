package neth.iecal.curbox.ui.fragments.main

import neth.iecal.curbox.R

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.databinding.FragmentInfoBinding
import neth.iecal.curbox.ui.fragments.main.reducers.sync.AccountController

class InfoFragment : Fragment() {

    private var _binding: FragmentInfoBinding? = null
    private val binding get() = _binding!!

    private var accountController: AccountController? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { accountController?.pairWith(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAccountSection()
        setupClickListeners()
    }

    // Account and sync live here in the Play Store build only. F-Droid stays
    // offline, so the section never appears and there is no login to be seen.
    private fun setupAccountSection() {
        if (BuildConfig.FDROID_VARIANT) return

        binding.cardAccount.visibility = View.VISIBLE
        accountController = AccountController(binding.cardAccount, this) {
            scanLauncher.launch(ScanOptions().setOrientationLocked(true).setPrompt("Point at the pairing code"))
        }.also { it.bind() }
    }

    private fun setupClickListeners() {
        binding.btnDocs.setOnClickListener {
            openUrl("https://curbox.app/docs/")
        }

        binding.btnSupport.setOnClickListener {
            openUrl("https://github.com/nethical6")
        }

        binding.btnDonate.setOnClickListener {
            openUrl("https://curbox.app/donate")
        }

        binding.btnShare.setOnClickListener {
            shareProject()
        }

        binding.cardDiscord.setOnClickListener {
            // Replace with actual Discord invite link
            openUrl("https://discord.com/invite/Vs9mwUtuCN")
        }

        binding.cardInstagram.setOnClickListener {
            openUrl("https://instagram.com/curbox.app")
        }

        binding.btnActionCrashLogs.setOnClickListener {
            showCrashLogs()
        }
    }

    private fun shareProject() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.share_curbox_message))
        }
        startActivity(Intent.createChooser(intent, null))
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showCrashLogs() {
        val logFile = File(requireContext().filesDir, "crash_log.txt")
        val content = if (logFile.exists()) {
            try {
                val text = logFile.readText()
                if (text.isBlank()) "No crash logs available." else text
            } catch (e: Exception) {
                "Error reading crash logs."
            }
        } else {
            "No crash logs available."
        }
        
        val displayContent = if (content.length > 50000) {
            "...${content.takeLast(50000)}"
        } else {
            content
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Crash Logs")
            .setMessage(displayContent)
            .setPositiveButton("Share") { _, _ ->
                shareCrashLogs(content)
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                if (logFile.exists() && logFile.delete()) {
                    Toast.makeText(requireContext(), getString(R.string.crash_logs_cleared), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun shareCrashLogs(content: String) {
        if (content == "No crash logs available." || content == "Error reading crash logs.") run {
            Toast.makeText(requireContext(), getString(R.string.nothing_to_share), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Curbox Crash Logs")
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, "Share Crash Logs"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        accountController = null
        _binding = null
    }
}
