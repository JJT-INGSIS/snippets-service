package com.jjt.ingsis.snippets.storage

sealed interface StoreResult {
    data class Stored(
        val reference: ContentReference,
    ) : StoreResult
}

sealed interface ReadResult {
    data class Found(
        val code: String,
    ) : ReadResult

    data object Missing : ReadResult
}

sealed interface DeleteResult {
    data object Deleted : DeleteResult
}

data object InvalidContentReference : ReadResult, DeleteResult

data object StorageUnavailable : StoreResult, ReadResult, DeleteResult
