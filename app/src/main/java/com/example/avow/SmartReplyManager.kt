package com.example.avow

import android.util.Log
import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplyGenerator
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage

class SmartReplyManager(private val onSuggestionsReady: (List<String>) -> Unit) {

    private val smartReply: SmartReplyGenerator = SmartReply.getClient()
    private val conversationHistory = mutableListOf<TextMessage>()

    fun addMessage(text: String, isLocalUser: Boolean) {
        if (text.isBlank()) return
        
        val timestamp = System.currentTimeMillis()
        val message = if (isLocalUser) {
            TextMessage.createForLocalUser(text, timestamp)
        } else {
            TextMessage.createForRemoteUser(text, timestamp, "context")
        }
        
        conversationHistory.add(message)
        
        // Keep history small to focus on immediate context
        if (conversationHistory.size > 10) {
            conversationHistory.removeAt(0)
        }

        generateSuggestions()
    }

    private fun generateSuggestions() {
        if (conversationHistory.isEmpty()) return

        smartReply.suggestReplies(conversationHistory)
            .addOnSuccessListener { result ->
                if (result.status == SmartReplySuggestionResult.STATUS_SUCCESS) {
                    val suggestions = result.suggestions.map { it.text }
                    onSuggestionsReady(suggestions)
                } else if (result.status == SmartReplySuggestionResult.STATUS_NOT_SUPPORTED_LANGUAGE) {
                    Log.e("SmartReply", "Language not supported")
                } else {
                    Log.e("SmartReply", "No suggestions found or error")
                }
            }
            .addOnFailureListener { e ->
                Log.e("SmartReply", "Error suggesting replies", e)
            }
    }

    fun clearHistory() {
        conversationHistory.clear()
        onSuggestionsReady(emptyList())
    }

    fun close() {
        smartReply.close()
    }
}
