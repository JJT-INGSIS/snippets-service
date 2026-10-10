package com.jjt.ingsis.snippets.storage

interface SnippetContentStorage {
    fun store(code: String): StoreResult

    fun read(reference: ContentReference): ReadResult

    fun delete(reference: ContentReference): DeleteResult
}
