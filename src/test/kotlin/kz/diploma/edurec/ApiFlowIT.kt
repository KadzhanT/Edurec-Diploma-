package kz.diploma.edurec

import com.jayway.jsonpath.JsonPath
import kz.diploma.edurec.student.Student
import kz.diploma.edurec.student.StudentRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer

/** End-to-end flow against a real MongoDB in Docker: auth, catalog CRUD, search, interactions, history and stats. */
@SpringBootTest(properties = ["app.seed.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
class ApiFlowIT {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var students: StudentRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun should_cover_main_api_flow_when_admin_and_student_use_platform() {
        students.save(Student(email = "admin@test.kz", passwordHash = passwordEncoder.encode("admin123")!!, fullName = "Admin", roles = setOf("ADMIN")))
        val admin = token(mvc.post("/api/auth/login") { json("""{"email":"admin@test.kz","password":"admin123"}""") }.andExpect { status { isOk() } })
        val student = token(
            mvc.post("/api/auth/register") {
                json("""{"email":"Alice@test.kz","password":"secret1","fullName":"Alice","yearOfStudy":2}""")
            }.andExpect { status { isCreated() } },
        )
        mvc.post("/api/auth/register") { json("""{"email":"alice@test.kz","password":"secret1","fullName":"Dup"}""") }
            .andExpect { status { isConflict() } }
        mvc.post("/api/auth/login") { json("""{"email":"alice@test.kz","password":"wrong"}""") }.andExpect { status { isUnauthorized() } }

        val course = """{"code":"DS201","name":"Machine Learning","description":"Supervised learning basics","category":"Data Science",
            "credits":5,"difficulty":3,"teachingLanguage":"EN","instructor":"G. M.","tags":["ml","python"]}"""
        mvc.post("/api/courses") { json(course); bearer(student) }.andExpect { status { isForbidden() } }
        val courseId = JsonPath.read<String>(
            mvc.post("/api/courses") { json(course); bearer(admin) }.andExpect { status { isCreated() } }.body(),
            "$.id",
        )

        mvc.get("/api/courses?q=learning&category=Data Science").andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
            jsonPath("$.items[0].code") { value("DS201") }
        }
        mvc.get("/api/courses?category=Mathematics").andExpect { jsonPath("$.total") { value(0) } }

        mvc.post("/api/interactions") { json("""{"courseId":"$courseId","type":"RATE","value":9}"""); bearer(student) }
            .andExpect { status { isBadRequest() } }
        mvc.post("/api/interactions") { json("""{"courseId":"$courseId","type":"ENROLL"}"""); bearer(student) }
            .andExpect { status { isCreated() } }
        mvc.post("/api/interactions") { json("""{"courseId":"$courseId","type":"RATE","value":4}"""); bearer(student) }
            .andExpect { status { isCreated() } }

        mvc.get("/api/students/me/interactions") { bearer(student) }.andExpect {
            jsonPath("$.total") { value(2) }
            jsonPath("$.items[0].type") { value("RATE") }
            jsonPath("$.items[0].courseName") { value("Machine Learning") }
        }
        mvc.get("/api/courses/$courseId").andExpect {
            jsonPath("$.enrollCount") { value(1) }
            jsonPath("$.avgRating") { value(4.0) }
        }
        mvc.get("/api/students/me") { bearer(student) }.andExpect { jsonPath("$.email") { value("alice@test.kz") } }

        // Recommendations: Bob co-takes ML and a second course, so Alice (ML only) gets that course recommended.
        val second = JsonPath.read<String>(
            mvc.post("/api/courses") { json(course.replace("DS201", "DS301").replace("Machine Learning", "Deep Learning")); bearer(admin) }.body(),
            "$.id",
        )
        val bob = token(mvc.post("/api/auth/register") { json("""{"email":"bob@test.kz","password":"secret1","fullName":"Bob"}""") })
        listOf(courseId, second).forEach { id ->
            mvc.post("/api/interactions") { json("""{"courseId":"$id","type":"ENROLL"}"""); bearer(bob) }.andExpect { status { isCreated() } }
        }
        mvc.post("/api/admin/recommendations/rebuild") { bearer(student) }.andExpect { status { isForbidden() } }
        mvc.post("/api/admin/recommendations/rebuild") { bearer(admin) }.andExpect {
            status { isOk() }
            jsonPath("$.courses") { value(2) }
        }
        mvc.get("/api/students/me/recommendations") { bearer(student) }.andExpect {
            status { isOk() }
            jsonPath("$[0].course.id") { value(second) }
            jsonPath("$[?(@.course.id == '$courseId')]") { isEmpty() } // already taken
        }
        mvc.get("/api/courses/$courseId/similar").andExpect {
            status { isOk() }
            jsonPath("$[0].course.code") { value("DS301") }
        }
        mvc.delete("/api/courses/$second") { bearer(admin) }.andExpect { status { isNoContent() } }

        mvc.delete("/api/courses/$courseId") { bearer(admin) }.andExpect { status { isNoContent() } }
        mvc.get("/api/courses/$courseId").andExpect { status { isNotFound() } }
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.json(body: String) {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    private fun ResultActionsDsl.body() = andReturn().response.contentAsString

    private fun token(result: ResultActionsDsl) = JsonPath.read<String>(result.body(), "$.token")
}
