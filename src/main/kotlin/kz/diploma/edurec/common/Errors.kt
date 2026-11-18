package kz.diploma.edurec.common

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class NotFoundException(message: String) : RuntimeException(message)

class ConflictException(message: String) : RuntimeException(message)

data class PageResponse<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)

@RestControllerAdvice
class ErrorHandler {
    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) = problem(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(ConflictException::class)
    fun conflict(e: ConflictException) = problem(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) = problem(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(BadCredentialsException::class)
    fun unauthorized(e: BadCredentialsException) = problem(HttpStatus.UNAUTHORIZED, "Invalid email or password")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException) =
        problem(HttpStatus.BAD_REQUEST, e.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" })

    private fun problem(status: HttpStatus, detail: String?) = ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase)
}
