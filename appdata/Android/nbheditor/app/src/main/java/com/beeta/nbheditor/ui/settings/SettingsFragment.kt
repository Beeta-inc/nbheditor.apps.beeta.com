package com.beeta.nbheditor.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.beeta.nbheditor.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textSettings
        
        try {
            val versionName = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
            textView.text = "NBH Editor v$versionName\n\nSettings"
        } catch (e: Exception) {
            textView.text = "NBH Editor v2.2.0\n\nSettings"
        }
        
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}