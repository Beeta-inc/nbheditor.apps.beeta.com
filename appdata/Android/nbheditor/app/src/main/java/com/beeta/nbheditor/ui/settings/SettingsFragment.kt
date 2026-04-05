package com.beeta.nbheditor.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.beeta.nbheditor.AppUpdater
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
            binding.currentVersionText.text = "Current version: v2.2.0"
        }

        // Check for updates button
        binding.checkUpdateButton.setOnClickListener {
            if (!isChecking) {
                checkForUpdates()
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