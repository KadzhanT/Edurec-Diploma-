package kz.diploma.edurec.course

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kz.diploma.edurec.common.PageResponse
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/api/courses")
@Tag(name = "Courses", description = "Course catalog: search is public, changes require ROLE_ADMIN")
class CourseController(private val service: CourseService) {
    @GetMapping
    @Operation(summary = "Search courses by text (name/description/tags), category and difficulty")
    fun search(
        @Parameter(description = "Full-text query") @RequestParam(required = false) q: String?,
        @Parameter(description = "Exact category, e.g. Data Science") @RequestParam(required = false) category: String?,
        @Parameter(description = "Difficulty 1..5") @RequestParam(required = false) difficulty: Int?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): PageResponse<CourseDto> = service.search(q, category, difficulty, page, size)

    @GetMapping("/{id}")
    @Operation(summary = "Get a course by id")
    fun get(@PathVariable id: String) = service.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a course (admin)")
    fun create(@Valid @RequestBody req: CourseRequest) = service.create(req)

    @PutMapping("/{id}")
    @Operation(summary = "Update a course (admin)")
    fun update(@PathVariable id: String, @Valid @RequestBody req: CourseRequest) = service.update(id, req)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a course (admin)")
    fun delete(@PathVariable id: String) = service.delete(id)
}
