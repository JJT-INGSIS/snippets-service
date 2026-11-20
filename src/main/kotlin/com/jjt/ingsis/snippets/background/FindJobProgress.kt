package com.jjt.ingsis.snippets.background

import com.jjt.ingsis.snippets.creation.canonicalUuid
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.permissions.AccessChecker
import com.jjt.ingsis.snippets.permissions.AccessLookup
import com.jjt.ingsis.snippets.permissions.AccessRelation
import org.springframework.stereotype.Service

sealed interface JobLookup {
    data class Found(
        val progress: JobProgress,
    ) : JobLookup

    data object Missing : JobLookup

    data object Invalid : JobLookup

    data object IdentityRequired : JobLookup

    data object Unavailable : JobLookup
}

@Service
class FindJobProgress(
    private val identity: ActorIdentityResolver,
    private val jobs: JobProgressStore,
    private val access: AccessChecker,
    private val snippets: SnippetMetadataStore,
) {
    fun find(
        id: String,
        context: ActorContext,
    ): JobLookup {
        val actor = identity.resolve(context) as? ActorIdentity.Identified ?: return JobLookup.IdentityRequired
        val uuid = canonicalUuid(id) ?: return JobLookup.Invalid
        return try {
            val job = jobs.find(uuid) ?: return JobLookup.Missing
            when (job.event.eventType) {
                EventType.ParserVersionActivated -> {
                    JobLookup.Missing
                }

                EventType.FormattingRulesChanged, EventType.LintRulesChanged -> {
                    if (job.event.scope["ownerId"] == actor.actorId) read(job) else JobLookup.Missing
                }

                EventType.SnippetUpdated -> {
                    snippet(job, actor.actorId)
                }
            }
        } catch (_: MetadataPersistenceException) {
            JobLookup.Unavailable
        }
    }

    private fun read(job: StoredJob): JobLookup =
        jobs.progress(job.event.jobId)?.let { JobLookup.Found(it) } ?: JobLookup.Missing

    private fun snippet(
        job: StoredJob,
        actor: String,
    ): JobLookup {
        val id = canonicalUuid(job.event.scope["snippetId"]) ?: return JobLookup.Missing
        if (snippets.findConfirmed(id) == null) return JobLookup.Missing
        return when (val permission = access.check(id, actor)) {
            is AccessLookup.Granted -> {
                if (permission.access.relation == AccessRelation.OWNED &&
                    permission.access.ownerId == actor
                ) {
                    read(job)
                } else {
                    JobLookup.Missing
                }
            }

            AccessLookup.Denied, AccessLookup.Missing -> {
                JobLookup.Missing
            }

            is AccessLookup.Failed -> {
                JobLookup.Unavailable
            }
        }
    }
}
