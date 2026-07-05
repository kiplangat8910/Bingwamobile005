package com.bingwa.mobile

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RechargeScannerUiState(
    val defaultSimSelection: Int = USSD_SIM_SELECTION_SLOT_1,
    val simConfigured: Boolean = false,
    val showInitialSimDialog: Boolean = true,
    val isImporting: Boolean = false,
    val message: String? = null,
    val history: List<ScratchCardHistoryItem> = emptyList()
)

class RechargeScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScratchCardRepository.get(application)
    private val recognizer = ScratchCardTextRecognizer(application)
    private val mutableState = MutableStateFlow(RechargeScannerUiState())

    val uiState: StateFlow<RechargeScannerUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.historyFlow.collectLatest { history ->
                mutableState.update { it.copy(history = history) }
            }
        }
        viewModelScope.launch {
            repository.defaultSimFlow.collectLatest { selection ->
                mutableState.update { it.copy(defaultSimSelection = selection) }
            }
        }
        viewModelScope.launch {
            repository.simConfiguredFlow.collectLatest { configured ->
                mutableState.update {
                    it.copy(
                        simConfigured = configured,
                        showInitialSimDialog = !configured
                    )
                }
            }
        }
    }

    fun setDefaultSim(simSelection: Int) {
        viewModelScope.launch {
            repository.setDefaultSim(simSelection)
            mutableState.update {
                it.copy(
                    defaultSimSelection = simSelection,
                    simConfigured = true,
                    showInitialSimDialog = false
                )
            }
        }
    }

    fun importFromCamera(bitmap: Bitmap?) {
        if (bitmap == null) {
            showMessage("No image was captured from the camera")
            return
        }
        processImport {
            recognizer.extractCodesFromBitmap(bitmap)
        }
    }

    fun importFromGallery(uris: List<Uri>) {
        if (uris.isEmpty()) {
            showMessage("No images were selected from the gallery")
            return
        }
        processImport {
            uris.flatMap { recognizer.extractCodesFromUri(it) }.distinct()
        }
    }

    fun updateCardSim(historyId: Long, simSelection: Int) {
        viewModelScope.launch {
            repository.updateHistorySim(historyId, simSelection)
            ScratchCardRechargeManager.onQueueUpdated(getApplication())
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            showMessage("Recharge scanner history cleared")
        }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun processImport(loadCodes: suspend () -> List<String>) {
        viewModelScope.launch {
            mutableState.update { it.copy(isImporting = true, message = null) }
            val recognized = runCatching { loadCodes() }
                .getOrElse {
                    mutableState.update {
                        it.copy(
                            isImporting = false,
                            message = "Could not read the selected image(s)"
                        )
                    }
                    return@launch
                }
            if (recognized.isEmpty()) {
                mutableState.update {
                    it.copy(
                        isImporting = false,
                        message = "No valid 16-digit recharge codes were found"
                    )
                }
                return@launch
            }
            val result = repository.enqueueRecognizedCodes(
                codes = recognized,
                simSelection = mutableState.value.defaultSimSelection
            )
            if (result.addedCount > 0) {
                ScratchCardRechargeManager.onQueueUpdated(getApplication())
            }
            mutableState.update {
                it.copy(
                    isImporting = false,
                    message = buildImportSummary(result)
                )
            }
        }
    }

    private fun showMessage(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    private fun buildImportSummary(result: ScratchCardEnqueueResult): String {
        if (result.totalRecognized == 0) return "No valid 16-digit recharge codes were found"
        return when {
            result.addedCount == 0 && result.skippedCount > 0 ->
                "Scanner queue is full. Clear history to add more cards"

            result.skippedCount > 0 ->
                "${result.addedCount} card(s) queued. ${result.skippedCount} skipped because the 50-card limit was reached"

            else ->
                "${result.addedCount} card(s) queued for recharge"
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return RechargeScannerViewModel(application) as T
                }
            }
        }
    }
}
