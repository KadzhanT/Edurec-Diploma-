package kz.diploma.edurec.course

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.index.TextIndexed
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository


@Document("courses")
@CompoundIndex(name = "category_difficulty", def = "{'category': 1, 'difficulty': 1}")
data class Course(
    @Id val id: String? = null,
    @Indexed(unique = true) val code: String,
    @TextIndexed(weight = 3f) val name: String,
    @TextIndexed val description: String,
    val category: String,
    val credits: Int,
    val difficulty: Int,
    val teachingLanguage: String,
    val instructor: String,
    @TextIndexed(weight = 2f) val tags: List<String> = emptyList(),
    val stats: CourseStats = CourseStats(),
)


data class CourseStats(val enrollCount: Long = 0, val ratingSum: Double = 0.0, val ratingCount: Long = 0)

interface CourseRepository : MongoRepository<Course, String> {
    fun existsByCode(code: String): Boolean
}

data class CourseRequest(
    @field:NotBlank val code: String,
    @field:NotBlank val name: String,
    @field:NotBlank val description: String,
    @field:NotBlank val category: String,
    @field:Min(1) @field:Max(30) val credits: Int,
    @field:Min(1) @field:Max(5) val difficulty: Int,
    @field:NotBlank val teachingLanguage: String,
    @field:NotBlank val instructor: String,
    val tags: List<String> = emptyList(),
)

data class CourseDto(
    val id: String,
    val code: String,
    val name: String,
    val description: String,
    val category: String,
    val credits: Int,
    val difficulty: Int,
    val teachingLanguage: String,
    val instructor: String,
    val tags: List<String>,
    val enrollCount: Long,
    val ratingCount: Long,
    val avgRating: Double?,
)

fun Course.toDto() = CourseDto(
    id!!, code, name, description, category, credits, difficulty, teachingLanguage, instructor, tags,
    stats.enrollCount, stats.ratingCount,
    if (stats.ratingCount == 0L) null else Math.round(stats.ratingSum / stats.ratingCount * 100) / 100.0,
)

fun CourseRequest.toCourse(id: String? = null, stats: CourseStats = CourseStats()) =
    Course(id, code, name, description, category, credits, difficulty, teachingLanguage, instructor, tags, stats)
