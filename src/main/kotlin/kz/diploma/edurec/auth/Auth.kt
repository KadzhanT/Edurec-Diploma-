package kz.diploma.edurec.auth

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.diploma.edurec.common.ConflictException
import kz.diploma.edurec.student.Student
import kz.diploma.edurec.student.StudentRepository
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

data class RegisterRequest(
    @field:Email @field:NotBlank val email: String,
    @field:Size(min = 6, max = 100) val password: String,
    @field:NotBlank val fullName: String,
    val faculty: String? = null,
    val group: String? = null,
    @field:Min(1) @field:Max(6) val yearOfStudy: Int? = null,
)

data class LoginRequest(@field:NotBlank val email: String, @field:NotBlank val password: String)

data class TokenResponse(val token: String, val expiresIn: Long)

@Service
class AuthService(
    private val students: StudentRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtEncoder: JwtEncoder,
) {
    private val ttl = Duration.ofHours(12)

    fun register(req: RegisterRequest): TokenResponse {
        val email = req.email.lowercase()
        if (students.existsByEmail(email)) throw ConflictException("Email $email is already registered")
        val student = students.save(
            Student(
                email = email,
                passwordHash = passwordEncoder.encode(req.password)!!,
                fullName = req.fullName,
                faculty = req.faculty,
                group = req.group,
                yearOfStudy = req.yearOfStudy,
            ),
        )
        return token(student)
    }

    fun login(req: LoginRequest): TokenResponse {
        val student = students.findByEmail(req.email.lowercase())
        if (student == null || !passwordEncoder.matches(req.password, student.passwordHash)) throw BadCredentialsException("bad credentials")
        return token(student)
    }

    private fun token(student: Student): TokenResponse {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .subject(student.id!!)
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .claim("email", student.email)
            .claim("roles", student.roles.toList())
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return TokenResponse(jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue, ttl.seconds)
    }
}

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Registration and login (returns JWT)")
class AuthController(private val auth: AuthService) {
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new student and get a token")
    fun register(@Valid @RequestBody req: RegisterRequest) = auth.register(req)

    @PostMapping("/login")
    @Operation(summary = "Log in and get a token")
    fun login(@Valid @RequestBody req: LoginRequest) = auth.login(req)
}
