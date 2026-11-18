package kz.diploma.edurec.interaction

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kz.diploma.edurec.common.NotFoundException
import kz.diploma.edurec.common.PageResponse
import kz.diploma.edurec.course.Course
import kz.diploma.edurec.course.CourseRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Service
class InteractionService(
    private val interactions: InteractionRepository,
    private val courses: CourseRepository,
    private val mongo: MongoTemplate,
) {
    fun record(studentId: String, req: InteractionRequest): InteractionDto {
        validateValue(req.type, req.value)
        val course = courses.findById(req.courseId).orElseThrow { NotFoundException("Course ${req.courseId} not found") }
        val saved = interactions.save(Interaction(studentId = studentId, courseId = req.courseId, type = req.type, value = req.value))

        val inc = when (req.type) {
            InteractionType.ENROLL -> Update().inc("stats.enrollCount", 1)
            InteractionType.RATE -> Update().inc("stats.ratingCount", 1).inc("stats.ratingSum", req.value!!)
            else -> null
        }
        inc?.let { mongo.updateFirst(Query(Criteria.where("_id").`is`(course.id)), it, Course::class.java) }
        return saved.toDto(course.name)
    }

    fun history(studentId: String, page: Int, size: Int): PageResponse<InteractionDto> {
        val items = interactions.findByStudentIdOrderByOccurredAtDesc(studentId, PageRequest.of(page, size))
        val names = courses.findAllById(items.map { it.courseId }.toSet()).associate { it.id to it.name }
        return PageResponse(items.map { it.toDto(names[it.courseId]) }, page, size, interactions.countByStudentId(studentId))
    }

    private fun validateValue(type: InteractionType, value: Double?) {
        when (type) {
            InteractionType.RATE -> require(value != null && value in 1.0..5.0) { "RATE needs value 1..5" }
            InteractionType.GRADE -> require(value != null && value in 0.0..100.0) { "GRADE needs value 0..100" }
            else -> require(value == null) { "$type does not take a value" }
        }
    }
}

@RestController
@Validated
@Tag(name = "Interactions", description = "History of student actions on courses")
class InteractionController(private val service: InteractionService) {
    @PostMapping("/api/interactions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record an interaction of the current student (VIEW, ENROLL, RATE 1..5, GRADE 0..100, COMPLETE)")
    fun record(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody req: InteractionRequest) = service.record(jwt.subject!!, req)

    @GetMapping("/api/students/me/interactions")
    @Operation(summary = "My interaction history, newest first")
    fun history(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ) = service.history(jwt.subject!!, page, size)
}
