package com.brewtech.decidr.voice

/**
 * Categories a spoken question can be classified into.
 */
enum class QuestionCategory {
    YES_NO,
    RELATIONSHIP,
    CAREER,
    HEALTH,
    TIMING,
    FEAR,
    GENERAL
}

/**
 * Parses spoken questions to detect their category using keyword matching.
 * If multiple categories match, the first match in priority order wins.
 */
object QuestionParser {

    private val categoryKeywords: List<Pair<QuestionCategory, List<String>>> = listOf(
        QuestionCategory.YES_NO to listOf(
            "should", "will", "would", "can", "is it", "do i", "does", "am i"
        ),
        QuestionCategory.RELATIONSHIP to listOf(
            "love", "relationship", "dating", "partner", "boyfriend", "girlfriend",
            "ex", "crush", "marry", "date"
        ),
        QuestionCategory.CAREER to listOf(
            "job", "work", "career", "boss", "quit", "money", "salary",
            "hire", "fired", "promotion"
        ),
        QuestionCategory.HEALTH to listOf(
            "health", "sick", "exercise", "diet", "sleep", "weight",
            "doctor", "pain"
        ),
        QuestionCategory.TIMING to listOf(
            "when", "how long", "how soon", "time", "today", "tomorrow"
        ),
        QuestionCategory.FEAR to listOf(
            "scared", "afraid", "worried", "anxious", "nervous", "fear"
        )
    )

    /**
     * Analyzes the question text and returns the most relevant category.
     * Matching is case-insensitive. Priority order matches the enum declaration.
     */
    fun parseQuestion(question: String): QuestionCategory {
        val lower = question.lowercase()

        for ((category, keywords) in categoryKeywords) {
            for (keyword in keywords) {
                if (lower.contains(keyword)) {
                    return category
                }
            }
        }

        return QuestionCategory.GENERAL
    }
}
