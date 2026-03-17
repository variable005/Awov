package com.example.avow

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import android.util.Log

class TranslationManager {

    private var hiToEnTranslator: Translator? = null
    private var enToHiTranslator: Translator? = null

    init {
        val hiToEnOptions = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.HINDI)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        hiToEnTranslator = Translation.getClient(hiToEnOptions)

        val enToHiOptions = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.HINDI)
            .build()
        enToHiTranslator = Translation.getClient(enToHiOptions)
    }

    suspend fun downloadModelsIfNeeded(): Boolean {
        return try {
            val conditions = DownloadConditions.Builder()
                .requireWifi()
                .build()
            
            hiToEnTranslator?.downloadModelIfNeeded(conditions)?.await()
            enToHiTranslator?.downloadModelIfNeeded(conditions)?.await()
            true
        } catch (e: Exception) {
            Log.e("TranslationManager", "Error downloading models", e)
            false
        }
    }

    suspend fun translateHiToEn(text: String): String {
        if (text.isBlank()) return ""
        return try {
            hiToEnTranslator?.translate(text)?.await() ?: text
        } catch (e: Exception) {
            Log.e("TranslationManager", "HI->EN Error", e)
            text
        }
    }

    suspend fun translateEnToHi(text: String): String {
        if (text.isBlank()) return ""
        return try {
            enToHiTranslator?.translate(text)?.await() ?: text
        } catch (e: Exception) {
            Log.e("TranslationManager", "EN->HI Error", e)
            text
        }
    }

    fun close() {
        hiToEnTranslator?.close()
        enToHiTranslator?.close()
    }
}
