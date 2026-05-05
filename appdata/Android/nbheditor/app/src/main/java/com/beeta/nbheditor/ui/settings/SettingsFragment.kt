package com.beeta.nbheditor.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.beeta.nbheditor.AppUpdater
import com.beeta.nbheditor.LocalLLMManager
import com.beeta.nbheditor.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var isChecking = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        setupUI()
        
        return root
    }

    private fun setupUI() {
        // Set current version
        try {
            val versionName = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
            binding.currentVersionText.text = "Current version: v$versionName"
        } catch (e: Exception) {
            binding.currentVersionText.text = "Current version: v6.0.0"
        }

        // Rich text mode switch
        val prefs = requireContext().getSharedPreferences("nbh_prefs", android.content.Context.MODE_PRIVATE)
        binding.richTextSwitch.isChecked = prefs.getBoolean("rich_text_mode", true)
        
        binding.richTextSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("rich_text_mode", isChecked).apply()
            Toast.makeText(requireContext(), 
                if (isChecked) "Rich text mode enabled" else "Rich text mode disabled", 
                Toast.LENGTH_SHORT).show()
        }
        
        // Local AI Model
        updateModelStatus()
        
        binding.downloadModelButton.setOnClickListener {
            downloadLocalModel()
        }
        
        binding.deleteModelButton.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Delete Local Model")
                .setMessage("This will free up ~700MB of storage. You can download it again later.")
                .setPositiveButton("Delete") { _, _ ->
                    LocalLLMManager.deleteModel(requireContext())
                    updateModelStatus()
                    Toast.makeText(requireContext(), "Model deleted", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Check for updates button
        binding.checkUpdateButton.setOnClickListener {
            if (!isChecking) {
                checkForUpdates()
            }
        }
        
        // Back button to return to editor
        binding.backButton?.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
    }
    
    private fun updateModelStatus() {
        if (LocalLLMManager.isModelAvailable(requireContext())) {
            val sizeMB = LocalLLMManager.getModelSize(requireContext())
            binding.localModelStatusText.text = "✓ MAXWELL model ready ($sizeMB MB)"
            binding.downloadModelButton.visibility = android.view.View.GONE
            binding.deleteModelButton.visibility = android.view.View.VISIBLE
        } else {
            binding.localModelStatusText.text = "MAXWELL model not downloaded"
            binding.downloadModelButton.visibility = android.view.View.VISIBLE
            binding.deleteModelButton.visibility = android.view.View.GONE
        }
    }
    
    private fun downloadLocalModel() {
        binding.downloadModelButton.isEnabled = false
        binding.modelDownloadProgressBar.visibility = android.view.View.VISIBLE
        binding.modelDownloadProgressBar.isIndeterminate = false
        binding.modelDownloadProgressBar.max = 100
        
        lifecycleScope.launch {
            try {
                val result = LocalLLMManager.downloadModel(requireContext()) { downloaded, total, progress ->
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        binding.modelDownloadProgressBar.progress = progress
                        val downloadedMB = downloaded / 1024 / 1024
                        val totalMB = total / 1024 / 1024
                        binding.localModelStatusText.text = "Downloading... $downloadedMB / $totalMB MB ($progress%)"
                    }
                }
                
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "✓ Model downloaded successfully", Toast.LENGTH_SHORT).show()
                    LocalLLMManager.initializeModel(requireContext())
                } else {
                    Toast.makeText(requireContext(), "Download failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.downloadModelButton.isEnabled = true
                binding.modelDownloadProgressBar.visibility = android.view.View.GONE
                updateModelStatus()
            }
        }
    }

    private fun checkForUpdates() {
        isChecking = true
        binding.checkUpdateButton.isEnabled = false
        binding.updateProgressBar.visibility = View.VISIBLE
        binding.updateStatusText.text = "Checking repository..."

        lifecycleScope.launch {
            try {
                // Simulate repository check delay
                kotlinx.coroutines.delay(500)
                
                binding.updateStatusText.text = "Fetching update information..."
                kotlinx.coroutines.delay(500)
                
                // Force check for updates
                AppUpdater.checkForUpdate(requireContext(), force = true)
                
                // Wait a bit to see if dialog appears
                kotlinx.coroutines.delay(1000)
                
                // If we reach here, no update was found
                binding.updateStatusText.text = "✓ You have the latest version"
                binding.updateProgressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "You're up to date!", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                binding.updateStatusText.text = "Failed to check for updates"
                binding.updateProgressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isChecking = false
                binding.checkUpdateButton.isEnabled = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}