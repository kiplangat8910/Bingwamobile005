package com.bingwa.mobile

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ScratchCardTextRecognizer {
    private val CODE_REGEX = Regex("\\b\\d{16}\\b")

    fun extractCodes(text: String): List<String> =
        CODE_REGEX.findAll(text)
            .map { it.value }
            .distinct()
            .toList()

    suspend fun recognizeFromBitmap(bitmap: Bitmap): List<String> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            extractCodes(recognizer.process(image).await().text.orEmpty())
        } finally {
            recognizer.close()
        }
    }

    suspend fun recognizeFromUri(context: Context, uri: Uri): List<String> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = InputImage.fromFilePath(context, uri)
            extractCodes(recognizer.process(image).await().text.orEmpty())
        } finally {
            recognizer.close()
        }
    }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }
