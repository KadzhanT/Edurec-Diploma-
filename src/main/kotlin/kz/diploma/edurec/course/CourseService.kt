package kz.diploma.edurec.course

import kz.diploma.edurec.common.ConflictException
import kz.diploma.edurec.common.NotFoundException
import kz.diploma.edurec.common.PageResponse
import org.springframework.data.domain.PageRequest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.TextCriteria
import org.springframework.data.mongodb.core.query.TextQuery
import org.springframework.stereotype.Service

@Service
class CourseService(private val courses: CourseRepository, private val mongo: MongoTemplate) {
    fun search(q: String?, category: String?, difficulty: Int?, page: Int, size: Int): PageResponse<CourseDto> {
        val query = if (q.isNullOrBlank()) Query() else TextQuery.queryText(TextCriteria.forDefaultLanguage().matching(q)).sortByScore()
        category?.let { query.addCriteria(Criteria.where("category").`is`(it)) }
        difficulty?.let { query.addCriteria(Criteria.where("difficulty").`is`(it)) }
        val total = mongo.count(query, Course::class.java)
        val items = mongo.find(query.with(PageRequest.of(page, size)), Course::class.java)
        return PageResponse(items.map { it.toDto() }, page, size, total)
    }

    fun get(id: String): CourseDto = find(id).toDto()

    fun create(req: CourseRequest): CourseDto {
        if (courses.existsByCode(req.code)) throw ConflictException("Course ${req.code} already exists")
        return courses.save(req.toCourse()).toDto()
    }

    fun update(id: String, req: CourseRequest): CourseDto {
        val existing = find(id)
        if (req.code != existing.code && courses.existsByCode(req.code)) throw ConflictException("Course ${req.code} already exists")
        return courses.save(req.toCourse(id, existing.stats)).toDto()
    }

    fun delete(id: String) = courses.delete(find(id))

    fun find(id: String): Course = courses.findById(id).orElseThrow { NotFoundException("Course $id not found") }
}
