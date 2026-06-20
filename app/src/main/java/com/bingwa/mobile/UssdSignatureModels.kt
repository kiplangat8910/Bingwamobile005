package com.bingwa.mobile

data class UssdSignatureStep(
    val stepIndex: Int = 0,
    val expectedInput: String = "",
    val menuTitle: String = "",
    val menuText: String = "",
    val selectedOptionLabel: String = "",
    val menuOptionsSnapshot: List<String> = emptyList()
)

data class UssdLearningCapture(
    val stepIndex: Int = -1,
    val enteredInput: String = "",
    val selectedOptionLabel: String = "",
    val popupText: String = ""
)

data class AdvancedDispatchResult(
    val finalResponse: String,
    val changeDetected: Boolean = false,
    val autoAdjusted: Boolean = false,
    val learningCompleted: Boolean = false,
    val suggestedCode: String = "",
    val changeSummary: String = "",
    val learnedSignature: List<UssdSignatureStep> = emptyList(),
    val learningCaptures: List<UssdLearningCapture> = emptyList(),
    val popupTranscript: List<String> = emptyList()
)
