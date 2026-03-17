package com.example.avow

object GestureLocalizer {
    private val hindiMappings = mapOf(
        Gesture.OPEN_PALM to "नमस्ते",
        Gesture.FIST to "मुझे मदद चाहिए",
        Gesture.TWO_FINGERS to "शुक्रिया",
        Gesture.THUMBS_UP to "हाँ",
        Gesture.THUMBS_DOWN to "नहीं",
        Gesture.INDEX_FINGER_UP to "मेरा एक सवाल है।",
        Gesture.THREE_FINGERS to "एक पल रुकिए।",
        Gesture.PINKY_UP to "मुझे पानी चाहिए।",
        Gesture.SHAKA to "बहुत अच्छे!",
        Gesture.PALM_DOWN to "शांत हो जाओ।",
        Gesture.LOVE_YOU to "मैं तुमसे प्यार करता हूँ!",
        Gesture.ROCK_ON to "बेहतरीन!",
        Gesture.PEACE_SIGN to "शांति बनी रहे।",
        Gesture.FOUR_FINGERS to "मुझे चार मिनट दीजिए।",
        Gesture.INDEX_PINCH to "समझ गया।",
        Gesture.TOUCHING_FINGERS to "सब कुछ जुड़ा हुआ है।",
        Gesture.WAVE to "नमस्ते!",
        Gesture.CIRCLE to "ठीक है।",
        Gesture.CLAP to "ध्यान दें!",
        Gesture.X_SIGN to "रुकिए।"
    )

    private val hindiHints = mapOf(
        Gesture.OPEN_PALM to "सभी उंगलियां फैली हुई, हथेली बाहर की ओर",
        Gesture.FIST to "सभी उंगलियां मुड़ी हुई, अंगूठा उंगलियों के ऊपर",
        Gesture.TWO_FINGERS to "तर्जनी और मध्यमा उंगलियां फैली हुई",
        Gesture.THUMBS_UP to "अंगूठा ऊपर, अन्य उंगलियां मुड़ी हुई",
        Gesture.THUMBS_DOWN to "अंगूठा नीचे, अन्य उंगलियां मुड़ी हुई",
        Gesture.INDEX_FINGER_UP to "केवल तर्जनी ऊपर की ओर",
        Gesture.THREE_FINGERS to "अंगूठा, तर्जनी और मध्यमा फैली हुई",
        Gesture.PINKY_UP to "केवल छोटी उंगली ऊपर की ओर",
        Gesture.SHAKA to "अंगूठा और छोटी उंगली फैली हुई",
        Gesture.PALM_DOWN to "सभी उंगलियां फैली हुई, हथेली जमीन की ओर",
        Gesture.LOVE_YOU to "अंगूठा, तर्जनी और छोटी उंगली फैली हुई",
        Gesture.X_SIGN to "तर्जनी उंगलियां एक-दूसरे को काटती हुई"
    )

    fun getSentence(gesture: Gesture, isHindi: Boolean): String {
        return if (isHindi) hindiMappings[gesture] ?: gesture.sentence else gesture.sentence
    }

    fun getHint(gesture: Gesture, isHindi: Boolean): String {
        return if (isHindi) hindiHints[gesture] ?: gesture.hint else gesture.hint
    }
}
