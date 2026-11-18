package kz.diploma.edurec.recommendation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kz.diploma.edurec.common.NotFoundException
import kz.diploma.edurec.course.Course
import kz.diploma.edurec.course.CourseDto
import kz.diploma.edurec.course.CourseRepository
import kz.diploma.edurec.course.toDto
import kz.diploma.edurec.interaction.Interaction
import kz.diploma.edurec.interaction.InteractionType
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import kotlin.math.sqrt

/** Precomputed item-item model: the top-K most similar courses of one course. Read by _id, no joins. */
@org.springframework.data.mongodb.core.mapping.Document("course_similarities")
data class CourseSimilarity(@Id val courseId: String, val neighbors: List<Neighbor>, val updatedAt: Instant = Instant.now())

data class Neighbor(val courseId: String, val score: Double)

interface CourseSimilarityRepository : MongoRepository<CourseSimilarity, String>

data class RecommendationDto(val course: CourseDto, val score: Double, val reason: String)

data class RebuildResult(val students: Int, val courses: Int, val pairs: Int, val millis: Long)

data class Scored(val courseId: String, val score: Double, val becauseOf: String?)

/** studentId -> (courseId -> preference score). */
typealias ScoreMatrix = Map<String, Map<String, Double>>

/** Item-based CF: cosine similarity between course columns of the student x course matrix, top [k] per course. */
fun itemSimilarities(scores: ScoreMatrix, k: Int): Map<String, List<Neighbor>> {
    val dot = HashMap<String, HashMap<String, Double>>()
    val norm = HashMap<String, Double>()
    for (row in scores.values) {
        for ((a, ra) in row) {
            norm.merge(a, ra * ra, Double::plus)
            for ((b, rb) in row) if (a != b) dot.getOrPut(a, ::HashMap).merge(b, ra * rb, Double::plus)
        }
    }
    return dot.mapValues { (a, row) ->
        row.map { (b, d) -> Neighbor(b, d / sqrt(norm.getValue(a) * norm.getValue(b))) }.sortedByDescending { it.score }.take(k)
    }
}

/** Score = sum of similarity x the student's preference over the courses already seen; seen courses are skipped. */
fun recommend(history: Map<String, Double>, neighbors: Map<String, List<Neighbor>>, limit: Int): List<Scored> {
    val total = HashMap<String, Double>()
    val best = HashMap<String, Pair<String, Double>>() // strongest contributor, used as the explanation
    for ((seen, r) in history) {
        for (n in neighbors[seen].orEmpty()) {
            if (n.courseId in history) continue
            val w = n.score * r
            total.merge(n.courseId, w, Double::plus)
            if (w > (best[n.courseId]?.second ?: 0.0)) best[n.courseId] = seen to w
        }
    }
    return total.entries.sortedByDescending { it.value }.take(limit).map { Scored(it.key, it.value, best[it.key]?.first) }
}

private fun Double.round3() = Math.round(this * 1000) / 1000.0

@Service
class RecommendationService(
    private val mongo: MongoTemplate,
    private val similarities: CourseSimilarityRepository,
    private val courses: CourseRepository,
    private val caches: CacheManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * One aggregation turns the event log into the preference matrix. An explicit rating wins;
     * otherwise the strongest implicit signal: VIEW 1, ENROLL 3, COMPLETE 4, GRADE value/20.
     */
    fun loadScores(studentId: String? = null): ScoreMatrix {
        fun case(type: InteractionType, then: Any) = Document("case", Document("\$eq", listOf("\$type", type.name))).append("then", then)
        val implicit = Document(
            "\$switch",
            Document(
                "branches",
                listOf(
                    case(InteractionType.VIEW, 1.0), case(InteractionType.ENROLL, 3.0), case(InteractionType.COMPLETE, 4.0),
                    case(InteractionType.GRADE, Document("\$divide", listOf("\$value", 20))),
                ),
            ).append("default", 0.0),
        )
        val rating = Document("\$cond", listOf(Document("\$eq", listOf("\$type", InteractionType.RATE.name)), "\$value", null))
        val pipeline = listOfNotNull(
            studentId?.let { Document("\$match", Document("studentId", it)) },
            Document(
                "\$group",
                Document("_id", Document("s", "\$studentId").append("c", "\$courseId"))
                    .append("rating", Document("\$max", rating))
                    .append("implicit", Document("\$max", implicit)),
            ),
        )
        val out = HashMap<String, HashMap<String, Double>>()
        mongo.getCollection(mongo.getCollectionName(Interaction::class.java)).aggregate(pipeline).allowDiskUse(true).forEach { d ->
            val id = d.get("_id", Document::class.java)
            val score = (d["rating"] as Number?)?.toDouble() ?: (d["implicit"] as Number).toDouble()
            out.getOrPut(id.getString("s"), ::HashMap)[id.getString("c")] = score
        }
        return out
    }

    /** Recomputes the whole item-item model. Called after seeding, every 10 minutes and by admins. */
    @Scheduled(fixedDelayString = "\${app.recs.rebuild-interval:PT10M}", initialDelayString = "\${app.recs.rebuild-interval:PT10M}")
    fun rebuild(): RebuildResult {
        val start = System.nanoTime()
        val scores = loadScores()
        val sims = itemSimilarities(scores, K)
        similarities.saveAll(sims.map { (c, n) -> CourseSimilarity(c, n.map { it.copy(score = it.score.round3()) }) }) // upsert by _id
        mongo.remove(Query(Criteria.where("_id").nin(sims.keys)), CourseSimilarity::class.java)
        listOf(RECS, SIMILAR).forEach { caches.getCache(it)?.clear() }
        val result = RebuildResult(scores.size, sims.size, sims.values.sumOf { it.size }, (System.nanoTime() - start) / 1_000_000)
        log.info("Recommendation model rebuilt: {}", result)
        return result
    }

    /** Cached per student; evicted when the student records a new interaction and on every rebuild. */
    // ponytail: unbounded ConcurrentMapCache, switch to Caffeine with size/TTL if memory matters
    @Cacheable(RECS)
    fun forStudent(studentId: String): List<RecommendationDto> = compute(studentId)

    /** Uncached path, public for the benchmark. */
    fun compute(studentId: String): List<RecommendationDto> {
        val history = loadScores(studentId)[studentId].orEmpty()
        val neighbors = similarities.findAllById(history.keys).associate { it.courseId to it.neighbors }
        val scored = recommend(history, neighbors, MAX)
        val byId = courses.findAllById(scored.map { it.courseId } + scored.mapNotNull { it.becauseOf }).associateBy { it.id }
        val personal = scored.mapNotNull { s ->
            byId[s.courseId]?.let { RecommendationDto(it.toDto(), s.score.round3(), "Students who liked ${byId[s.becauseOf]?.name ?: "your courses"} also chose this") }
        }
        if (personal.size >= MAX) return personal
        // Cold start (no history yet) or a small catalog: fill with the most popular unseen courses.
        val skip = history.keys + personal.map { it.course.id }
        val popular = mongo.find(Query().with(Sort.by(Sort.Direction.DESC, "stats.enrollCount")).limit(MAX + skip.size), Course::class.java)
            .filter { it.id !in skip }.take(MAX - personal.size)
            .map { RecommendationDto(it.toDto(), 0.0, "Popular among students (${it.stats.enrollCount} enrolled)") }
        return personal + popular
    }

    @Cacheable(SIMILAR)
    fun similar(courseId: String): List<RecommendationDto> {
        if (!courses.existsById(courseId)) throw NotFoundException("Course $courseId not found")
        val neighbors = similarities.findById(courseId).map { it.neighbors }.orElse(emptyList())
        val byId = courses.findAllById(neighbors.map { it.courseId }).associateBy { it.id }
        return neighbors.mapNotNull { n -> byId[n.courseId]?.let { RecommendationDto(it.toDto(), n.score, "Cosine similarity ${n.score}") } }
    }

    companion object {
        const val RECS = "recs"
        const val SIMILAR = "similar"
        const val K = 20
        const val MAX = 50
    }
}

@RestController
@Validated
@Tag(name = "Recommendations", description = "Item-based collaborative filtering over the interaction history")
class RecommendationController(private val service: RecommendationService) {
    @GetMapping("/api/students/me/recommendations")
    @Operation(summary = "Personalized course recommendations for the current student")
    fun mine(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) limit: Int,
    ) = service.forStudent(jwt.subject!!).take(limit)

    @GetMapping("/api/courses/{id}/similar")
    @Operation(summary = "Courses most similar to this one (students who took it also took...)")
    fun similar(@PathVariable id: String, @RequestParam(defaultValue = "5") @Min(1) @Max(20) limit: Int) = service.similar(id).take(limit)

    @PostMapping("/api/admin/recommendations/rebuild")
    @Operation(summary = "Recompute the item-item similarity model now (admin)")
    fun rebuild() = service.rebuild()
}
