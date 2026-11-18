package kz.diploma.edurec.recommendation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class RecommendationTest {
    // ml and dl are always taken together; history is taken by different people.
    private val scores: ScoreMatrix = mapOf(
        "s1" to mapOf("ml" to 5.0, "dl" to 5.0),
        "s2" to mapOf("ml" to 4.0, "dl" to 4.0, "sql" to 3.0),
        "s3" to mapOf("history" to 5.0, "sql" to 1.0),
        "s4" to mapOf("history" to 4.0),
    )

    @Test
    fun should_rank_co_taken_course_first_when_computing_similarities() {
        val sims = itemSimilarities(scores, 10)
        assertEquals("dl", sims.getValue("ml").first().courseId)
        assertEquals(1.0, sims.getValue("ml").first().score, 1e-9) // identical columns
        assertTrue(sims.getValue("ml").none { it.courseId == "history" }) // never co-taken -> no pair
    }

    @Test
    fun should_recommend_similar_unseen_courses_when_student_has_history() {
        val recs = recommend(mapOf("ml" to 5.0), itemSimilarities(scores, 10), 10)
        assertEquals("dl", recs.first().courseId)
        assertEquals("ml", recs.first().becauseOf)
        assertTrue(recs.none { it.courseId == "ml" }) // seen courses are excluded
    }

    @Test
    fun should_return_nothing_when_student_has_no_history() {
        assertTrue(recommend(emptyMap(), itemSimilarities(scores, 10), 10).isEmpty())
    }
}
