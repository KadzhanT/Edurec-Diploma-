package kz.diploma.edurec.interaction

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.diploma.edurec.course.Course
import kz.diploma.edurec.course.CourseRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.mongodb.core.MongoTemplate
import java.util.Optional

class InteractionServiceTest {
    private val interactions = mockk<InteractionRepository>()
    private val courses = mockk<CourseRepository>()
    private val mongo = mockk<MongoTemplate>(relaxed = true)
    private val service = InteractionService(interactions, courses, mongo)

    private val course = Course("c1", "DS201", "ML", "d", "Data Science", 5, 3, "EN", "x")

    @Test
    fun should_reject_when_rating_out_of_range() {
        assertThrows<IllegalArgumentException> { service.record("s1", InteractionRequest("c1", InteractionType.RATE, 6.0)) }
        assertThrows<IllegalArgumentException> { service.record("s1", InteractionRequest("c1", InteractionType.GRADE, null)) }
        assertThrows<IllegalArgumentException> { service.record("s1", InteractionRequest("c1", InteractionType.VIEW, 1.0)) }
        verify(exactly = 0) { interactions.save(any()) }
    }

    @Test
    fun should_update_course_stats_when_rating_recorded() {
        every { courses.findById("c1") } returns Optional.of(course)
        every { interactions.save(any()) } answers { firstArg<Interaction>().copy(id = "i1") }

        service.record("s1", InteractionRequest("c1", InteractionType.RATE, 4.0))
        service.record("s1", InteractionRequest("c1", InteractionType.VIEW))

        verify(exactly = 1) { mongo.updateFirst(any(), any(), Course::class.java) }
    }
}
