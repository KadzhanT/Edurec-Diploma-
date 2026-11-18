package kz.diploma.edurec.student

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

@Document("students")
data class Student(
    @Id val id: String? = null,
    @Indexed(unique = true) val email: String,
    val passwordHash: String,
    val fullName: String,
    val faculty: String? = null,
    val group: String? = null,
    val yearOfStudy: Int? = null,
    val roles: Set<String> = setOf("STUDENT"),
    val createdAt: Instant = Instant.now(),
)

interface StudentRepository : MongoRepository<Student, String> {
    fun findByEmail(email: String): Student?

    fun existsByEmail(email: String): Boolean
}

data class StudentDto(
    val id: String,
    val email: String,
    val fullName: String,
    val faculty: String?,
    val group: String?,
    val yearOfStudy: Int?,
    val roles: Set<String>,
    val createdAt: Instant,
)

data class UpdateProfileRequest(
    @field:NotBlank val fullName: String,
    val faculty: String? = null,
    val group: String? = null,
    @field:Min(1) @field:Max(6) val yearOfStudy: Int? = null,
)

fun Student.toDto() = StudentDto(id!!, email, fullName, faculty, group, yearOfStudy, roles, createdAt)
