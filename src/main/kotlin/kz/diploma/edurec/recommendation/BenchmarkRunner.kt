package kz.diploma.edurec.recommendation

import kz.diploma.edurec.config.DataSeeder
import kz.diploma.edurec.course.Course
import kz.diploma.edurec.interaction.Interaction
import kz.diploma.edurec.student.Student
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import java.io.File
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Performance test: reseeds a separate database at growing sizes and times recommendation retrieval
 * A) on-the-fly CF per request, B) precomputed model without index, C) + index, D) + cache.
 * Run: see README "Performance testing". Writes build/bench/results.csv and exits.
 */
@Component
@ConditionalOnProperty("app.bench.enabled", havingValue = "true")
class BenchmarkRunner(
    private val seeder: DataSeeder,
    private val service: RecommendationService,
    private val mongo: MongoTemplate,
    private val context: ConfigurableApplicationContext,
    @Value("\${app.bench.sizes:200,1000,5000,20000}") private val sizes: List<Int>,
    @Value("\${app.bench.samples:100}") private val samples: Int,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        // It wipes collections: never point it at the real database.
        require("bench" in mongo.db.name) { "Benchmark wipes data; use a database whose name contains 'bench' (got ${mongo.db.name})" }
        val header = "students,interactions,rebuild_ms,a_p50,a_p95,b_p50,b_p95,c_p50,c_p95,d_p50,d_p95," +
            "b_docs_examined,c_docs_examined,c_keys_examined,hit10_cf,hit10_popular"
        val rows = sizes.map { n -> runSize(n).also { log.info("BENCH {}", it) } }
        File("build/bench").mkdirs()
        File("build/bench/results.csv").writeText((listOf(header) + rows).joinToString("\n", postfix = "\n"))
        println("\n$header\n" + rows.joinToString("\n"))
        exitProcess(SpringApplication.exit(context))
    }

    private fun runSize(n: Int): String {
        listOf(Interaction::class.java, Course::class.java, Student::class.java, CourseSimilarity::class.java)
            .forEach { mongo.remove(Query(), it) } // remove keeps the indexes, dropCollection would not
        seeder.seed(n)
        val interactions = mongo.count(Query(), Interaction::class.java)
        val rebuildMs = service.rebuild().millis

        val ids = mongo.findDistinct(Query(), "studentId", Interaction::class.java, String::class.java).shuffled(Random(7)).take(samples)

        // A: no precomputation, the whole model is rebuilt from the event log on every request.
        val a = measure(ids.take(20)) { recommend(service.loadScores(it).getValue(it), itemSimilarities(service.loadScores(), RecommendationService.K), 10) }

        val indexOps = mongo.indexOps(Interaction::class.java)
        indexOps.dropIndex("student_time")
        val b = measure(ids) { service.compute(it) }
        val bExplain = explain(ids.first())

        indexOps.createIndex(Index().on("studentId", Sort.Direction.ASC).on("occurredAt", Sort.Direction.DESC).named("student_time"))
        val c = measure(ids) { service.compute(it) }
        val cExplain = explain(ids.first())

        ids.forEach { service.forStudent(it) } // warm the cache
        val d = measure(ids) { service.forStudent(it) }

        val (hitCf, hitPop) = hitRate(service.loadScores())
        return listOf(
            n, interactions, rebuildMs, a.first, a.second, b.first, b.second, c.first, c.second, d.first, d.second,
            bExplain.first, cExplain.first, cExplain.second, hitCf, hitPop,
        ).joinToString(",")
    }

    /** p50 and p95 latency in ms, after one warm-up pass. */
    private fun measure(ids: List<String>, call: (String) -> Unit): Pair<String, String> {
        ids.take(5).forEach(call)
        val ms = ids.map { id -> System.nanoTime().also { call(id) }.let { (System.nanoTime() - it) / 1e6 } }.sorted()
        fun p(q: Double) = "%.3f".format(java.util.Locale.ROOT, ms[((ms.size - 1) * q).toInt()])
        return p(0.5) to p(0.95)
    }

    /** totalDocsExamined / totalKeysExamined of the per-student history lookup. */
    private fun explain(studentId: String): Pair<Int, Int> {
        val cmd = Document("explain", Document("find", "interactions").append("filter", Document("studentId", studentId)))
            .append("verbosity", "executionStats")
        val stats = mongo.db.runCommand(cmd).get("executionStats", Document::class.java)
        return stats.getInteger("totalDocsExamined") to stats.getInteger("totalKeysExamined")
    }

    /** Leave-one-out hit-rate@10: hide one enrolled course per student, train without it, check if it is recommended. */
    private fun hitRate(scores: ScoreMatrix): Pair<String, String> {
        val rnd = Random(1)
        val hidden = scores.filterValues { it.size >= 3 }
            .mapNotNull { (s, row) -> row.filterValues { it >= 3.0 }.keys.randomOrNull(rnd)?.let { s to it } }.toMap()
        val train = scores.mapValues { (s, row) -> hidden[s]?.let { row - it } ?: row }
        val sims = itemSimilarities(train, RecommendationService.K)
        val popularity = train.values.flatMap { it.keys }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        var cf = 0
        var pop = 0
        for ((s, course) in hidden) {
            val seen = train.getValue(s)
            if (recommend(seen, sims, 10).any { it.courseId == course }) cf++
            if (course in popularity.filter { it !in seen }.take(10)) pop++
        }
        fun rate(x: Int) = "%.3f".format(java.util.Locale.ROOT, x.toDouble() / hidden.size)
        return rate(cf) to rate(pop)
    }
}
