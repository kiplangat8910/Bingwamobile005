package com.bingwa.mobile

data class OfferItem(
    val id: Int,
    val name: String,
    val price: Int,
    val ussdCode: String,
    var enabled: Boolean = true,
    val executionMode: String = "ADVANCED",
    val category: String = "Data",
    val targetDevice: String = "PRIMARY",
    val signatureDetectionEnabled: Boolean = false,
    val signatureAction: String = "STOP",
    val learnedSignature: List<UssdSignatureStep> = emptyList(),
    val signatureLearnedAt: Long = 0L,
    val signatureLearningCaptures: List<UssdLearningCapture> = emptyList(),
    val pendingLearnedSignature: List<UssdSignatureStep> = emptyList(),
    val pendingSignatureLearnedAt: Long = 0L,
    val pendingSignatureLearningCaptures: List<UssdLearningCapture> = emptyList()
)

data class SavedContact(val name: String, val phone: String)

