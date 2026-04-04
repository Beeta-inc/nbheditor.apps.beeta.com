package com.beeta.nbheditor.ui.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SettingsViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "NBH Editor v2.2.0\n\nSettings"
    }
    val text: LiveData<String> = _text
}