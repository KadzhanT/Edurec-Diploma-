package kz.diploma.edurec.student

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kz.diploma.edurec.common.NotFoundException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/students/me")
@Tag(name = "Students", description = "Profile of the current student")
class StudentController(private val students: StudentRepository) {
    @GetMapping
    @Operation(summary = "Get my profile")
    fun me(@AuthenticationPrincipal jwt: Jwt): StudentDto = find(jwt).toDto()

    @PutMapping
    @Operation(summary = "Update my profile")
    fun update(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody req: UpdateProfileRequest): StudentDto =
        students.save(
            find(jwt).copy(fullName = req.fullName, faculty = req.faculty, group = req.group, yearOfStudy = req.yearOfStudy),
        ).toDto()

    private fun find(jwt: Jwt) = students.findById(jwt.subject!!).orElseThrow { NotFoundException("Student not found") }
}
