package com.bingwa.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ScratchCardTextRecognizer(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractCodesFromBitmap(bitmap: Bitmap): List<String> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).await()
        return extractCodesFromText(result.text)
    }

    suspend fun extractCodesFromUri(uri: Uri): List<String> {
        val bitmap = loadBitmap(uri) ?: return emptyList()
        return extractCodesFromBitmap(bitmap)
    }

    private suspend fun loadBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = false
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        }.getOrElse {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }
    }

    companion object {
        val CODE_REGEX = Regex("""\b\d{16}\b""")

        fun extractCodesFromText(text: String): List<String> {
            return CODE_REGEX.findAll(text)
                .map { it.value }
                .distinct()
                .toList()
        }
    }
}
