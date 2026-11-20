package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.background.FindJobProgress
import com.jjt.ingsis.snippets.background.JobLookup
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class JobProgressController(
    private val progress: FindJobProgress,
) {
    @GetMapping("/jobs/{jobId}")
    fun find(
        @PathVariable jobId: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> =
        when (val result = progress.find(jobId, headers.actorContext())) {
            is JobLookup.Found -> ResponseEntity.ok(result.progress)
            JobLookup.Missing -> problem(HttpStatus.NOT_FOUND, "Job not available")
            JobLookup.Invalid -> problem(HttpStatus.BAD_REQUEST, "Invalid job id")
            JobLookup.IdentityRequired -> problem(HttpStatus.UNAUTHORIZED, "Identity required")
            JobLookup.Unavailable -> problem(HttpStatus.SERVICE_UNAVAILABLE, "Progress temporarily unavailable")
        }

    private fun problem(
        status: HttpStatus,
        detail: String,
    ): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail))
}
