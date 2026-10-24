package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.users.SearchUsers
import com.jjt.ingsis.snippets.users.UserSearchOutcome
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/users")
class UsersController(
    private val searchUsers: SearchUsers,
) {
    @GetMapping
    fun search(
        @RequestParam parameters: MultiValueMap<String, String>,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> =
        when (val parsed = userQueryOf(parameters)) {
            is ParsedUserSearch.Invalid -> SnippetProblem.INVALID_REQUEST.response("parameter", parsed.parameter)
            is ParsedUserSearch.Valid -> respond(searchUsers.search(parsed.query, headers.actorContext()))
        }

    private fun respond(outcome: UserSearchOutcome): ResponseEntity<*> =
        when (outcome) {
            is UserSearchOutcome.Found -> ResponseEntity.ok(UserPageResponse.from(outcome.page, outcome.result))
            UserSearchOutcome.IdentityRejected -> SnippetProblem.IDENTITY_UNAVAILABLE.response()
        }
}
