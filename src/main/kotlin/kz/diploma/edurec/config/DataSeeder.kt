package kz.diploma.edurec.config

import kz.diploma.edurec.course.Course
import kz.diploma.edurec.course.CourseRepository
import kz.diploma.edurec.course.CourseStats
import kz.diploma.edurec.interaction.Interaction
import kz.diploma.edurec.interaction.InteractionRepository
import kz.diploma.edurec.interaction.InteractionType
import kz.diploma.edurec.student.Student
import kz.diploma.edurec.student.StudentRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.Random
import kotlin.math.roundToInt

/**
 * Fills an empty database with synthetic, reproducible data (fixed seed).
 * Grades depend on a hidden student "ability", course difficulty and study year, so the future AutoML model has a real signal to learn.
 */
@Component
@ConditionalOnProperty("app.seed.enabled", havingValue = "true")
class DataSeeder(
    private val students: StudentRepository,
    private val courses: CourseRepository,
    private val interactions: InteractionRepository,
    private val passwordEncoder: PasswordEncoder,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rnd = Random(42)

    override fun run(args: ApplicationArguments) {
        if (courses.count() > 0) return log.info("Seed skipped: database is not empty")

        val savedCourses = courses.saveAll(catalog())
        val savedStudents = students.saveAll(demoAccounts() + syntheticStudents(200))

        val generated = savedStudents.filter { "STUDENT" in it.roles }.flatMap { studentHistory(it, savedCourses) }
        interactions.saveAll(generated)

        val byCourse = generated.groupBy { it.courseId }
        courses.saveAll(
            savedCourses.map { c ->
                val list = byCourse[c.id].orEmpty()
                val ratings = list.filter { it.type == InteractionType.RATE }
                c.copy(
                    stats = CourseStats(
                        enrollCount = list.count { it.type == InteractionType.ENROLL }.toLong(),
                        ratingSum = ratings.sumOf { it.value!! },
                        ratingCount = ratings.size.toLong(),
                    ),
                )
            },
        )
        log.info("Seeded {} courses, {} students, {} interactions", savedCourses.size, savedStudents.size, generated.size)
    }

    private fun demoAccounts() = listOf(
        Student(email = "admin@demo.kz", passwordHash = passwordEncoder.encode("admin123")!!, fullName = "Platform Admin", roles = setOf("ADMIN")),
        Student(
            email = "student@demo.kz", passwordHash = passwordEncoder.encode("student123")!!, fullName = "Demo Student",
            faculty = "Information Technology", group = "IT-23-1", yearOfStudy = 2,
        ),
    )

    private fun syntheticStudents(n: Int): List<Student> {
        val hash = passwordEncoder.encode("password123")!! // one hash for all: BCrypt is slow on purpose
        val firstNames = listOf("Aruzhan", "Dias", "Aigerim", "Nursultan", "Dana", "Yerlan", "Madina", "Timur", "Kamila", "Arman")
        val lastNames = listOf("Akhmetov", "Seitkali", "Nurlanova", "Bekov", "Omarova", "Zhaksylyk", "Ismailov", "Kassymova")
        val faculties = mapOf("Information Technology" to "IT", "Mathematics" to "MATH", "Economics" to "ECON", "Engineering" to "ENG")
        return (1..n).map { i ->
            val (faculty, abbr) = faculties.entries.elementAt(rnd.nextInt(faculties.size))
            val year = 1 + rnd.nextInt(4)
            Student(
                email = "student$i@demo.kz", passwordHash = hash,
                fullName = "${firstNames[rnd.nextInt(firstNames.size)]} ${lastNames[rnd.nextInt(lastNames.size)]}",
                faculty = faculty, group = "$abbr-${26 - year}-${1 + rnd.nextInt(3)}", yearOfStudy = year,
            )
        }
    }

    private fun studentHistory(student: Student, catalog: List<Course>): List<Interaction> {
        val ability = rnd.nextGaussian()
        val year = student.yearOfStudy ?: 1
        return catalog.shuffled(rnd).take(5 + rnd.nextInt(6)).flatMap { course ->
            var t = Instant.now().minus(Duration.ofDays(30L + rnd.nextInt(330)))
            fun next(type: InteractionType, value: Double? = null): Interaction {
                t = t.plus(Duration.ofHours(1L + rnd.nextInt(24 * 20)))
                return Interaction(studentId = student.id!!, courseId = course.id!!, type = type, value = value, occurredAt = t)
            }

            val events = MutableList(1 + rnd.nextInt(3)) { next(InteractionType.VIEW) }
            if (rnd.nextDouble() < 0.15) return@flatMap events // viewed only, never enrolled
            events += next(InteractionType.ENROLL)
            if (rnd.nextDouble() < 0.1) return@flatMap events // dropped out before the exam

            val grade = (70 + 12 * ability - 6 * (course.difficulty - 3) + 2 * (year - 2) + 8 * rnd.nextGaussian()).coerceIn(0.0, 100.0)
            events += next(InteractionType.GRADE, grade.roundToInt().toDouble())
            if (grade >= 50) events += next(InteractionType.COMPLETE)
            if (rnd.nextDouble() < 0.6) {
                val rating = (3 + (grade - 70) / 15 + 0.7 * rnd.nextGaussian()).roundToInt().coerceIn(1, 5)
                events += next(InteractionType.RATE, rating.toDouble())
            }
            events
        }
    }

    private fun catalog(): List<Course> {
        data class Category(val name: String, val prefix: String, val tags: List<String>, val courses: List<String>)

        val categories = listOf(
            Category(
                "Programming", "CS", listOf("coding", "software"),
                listOf("Introduction to Programming", "Object-Oriented Programming", "Algorithms and Data Structures", "Web Development", "Mobile Development"),
            ),
            Category(
                "Data Science", "DS", listOf("data", "analytics"),
                listOf("Introduction to Data Science", "Machine Learning", "Deep Learning", "Explainable AI", "AutoML in Practice"),
            ),
            Category(
                "Mathematics", "MATH", listOf("math", "theory"),
                listOf("Calculus I", "Linear Algebra", "Probability and Statistics", "Discrete Mathematics", "Numerical Methods"),
            ),
            Category(
                "Databases", "DB", listOf("data", "storage"),
                listOf("Relational Databases", "NoSQL Databases", "Data Warehousing", "Big Data Processing", "Database Administration"),
            ),
            Category(
                "Networks and Security", "NET", listOf("security", "infrastructure"),
                listOf("Computer Networks", "Information Security", "Cryptography", "Cloud Computing", "Operating Systems"),
            ),
            Category(
                "Economics", "ECON", listOf("business", "finance"),
                listOf("Microeconomics", "Macroeconomics", "Econometrics", "Digital Economy", "Project Management"),
            ),
            Category(
                "Languages", "LANG", listOf("language", "communication"),
                listOf("Academic English", "Kazakh Language", "Russian Language", "Technical Writing", "Public Speaking"),
            ),
            Category(
                "Humanities", "HUM", listOf("society", "history"),
                listOf("History of Kazakhstan", "Philosophy", "Sociology", "Psychology of Learning", "Ethics of AI"),
            ),
        )
        val instructors = listOf("A. Sarsenbayev", "G. Mukhanova", "R. Tulegenov", "S. Abenova", "K. Dzhaksybekov", "L. Ivanova")
        val languages = listOf("EN", "RU", "KZ")
        return categories.flatMap { cat ->
            cat.courses.mapIndexed { i, name ->
                val difficulty = (1 + i + rnd.nextInt(2)).coerceAtMost(5)
                Course(
                    code = "${cat.prefix}${101 + i * 100}",
                    name = name,
                    description = "$name: a ${cat.name.lowercase()} course for undergraduate students, difficulty level $difficulty of 5.",
                    category = cat.name,
                    credits = listOf(3, 4, 5, 6)[rnd.nextInt(4)],
                    difficulty = difficulty,
                    teachingLanguage = languages[rnd.nextInt(languages.size)],
                    instructor = instructors[rnd.nextInt(instructors.size)],
                    tags = cat.tags + name.lowercase().split(" ").filter { it.length > 3 },
                )
            }
        }
    }
}
