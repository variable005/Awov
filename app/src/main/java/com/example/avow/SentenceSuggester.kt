package com.example.avow

class SentenceSuggester {
    private val phraseMap = mapOf(
        "HELLO" to listOf("How are you?", "Good morning!", "Nice to see you."),
        "WATER" to listOf("I'm thirsty.", "Can I have some water?", "Where is the water?"),
        "HELP" to listOf("I need assistance.", "Something is wrong.", "Please help me."),
        "THANK YOU" to listOf("You're welcome!", "Glad to help.", "No problem."),
        "YES" to listOf("I agree.", "That's correct.", "Sure thing."),
        "NO" to listOf("I don't think so.", "No, thank you.", "Not right now."),
        "COFFEE" to listOf("I'd like a coffee.", "Is there any coffee left?", "Smells like coffee."),
        "AWESOME" to listOf("That's great!", "Way to go!", "I'm so happy!"),
        "I LOVE YOU" to listOf("I love you too.", "You're the best.", "Big hugs!")
    )

    fun getSuggestions(keywords: List<String>): List<String> {
        val lastKeyword = keywords.lastOrNull()?.uppercase() ?: return emptyList()
        return phraseMap[lastKeyword] ?: emptyList()
    }
}
