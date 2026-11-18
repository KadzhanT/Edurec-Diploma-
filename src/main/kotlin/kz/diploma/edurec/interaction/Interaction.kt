package kz.diploma.edurec.interaction

import jakarta.validation.constraints.NotBlank
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

enum class InteractionType { VIEW, ENROLL, RATE, GRADE, COMPLETE }

@Document("interactions")
@CompoundIndex(name = "student_time", def = "{'studentId': 1, 'occurredAt': -1}")
@CompoundIndex(name = "course_type", def = "{'courseId': 1, 'type': 1}")
data class Interaction(
    @Id val id: String? = null,
    val studentId: String,
    val courseId: String,
    val type: InteractionType,
    /** RATE: 1..5, GRADE: 0..100, other types: null. */
    val value: Double? = null,
    val occurredAt: Instant = Instant.now(),
)

interface InteractionRepository : MongoRepository<Interaction, String> {
    fun findByStudentIdOrderByOccurredAtDesc(studentId: String, pageable: Pageable): List<Interaction>

    fun countByStudentId(studentId: String): Long
}

data class InteractionRequest(@field:NotBlank val courseId: String, val type: InteractionType, val value: Double? = null)

data class InteractionDto(
    val id: String,
    val courseId: String,
    val courseName: String?,
    val type: InteractionType,
    val value: Double?,
    val occurredAt: Instant,
)

fun Interaction.toDto(courseName: String?) = InteractionDto(id!!, courseId, courseName, type, value, occurredAt)
