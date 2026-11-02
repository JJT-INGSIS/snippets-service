package com.jjt.ingsis.snippets.update

import com.jjt.ingsis.snippets.automatic.ObsoleteContent
import com.jjt.ingsis.snippets.automatic.SnippetUpdateCommit
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.storage.SnippetContentStorage

internal fun replacementForTest(
    storage: SnippetContentStorage,
    links: SnippetContentLinks,
): SnippetReplacement =
    SnippetReplacement(
        storage = storage,
        commits =
            SnippetUpdateCommit { candidate, reference ->
                links.replaceContent(candidate.snippetId, candidate.replacement.details(), reference)
            },
        obsolete = ObsoleteContent { storage.delete(it) },
    )
