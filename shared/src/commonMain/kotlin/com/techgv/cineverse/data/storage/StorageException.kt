package com.techgv.cineverse.data.storage

/** A known persistence failure, not empty data or a transport error. Platform subclasses carry recovery details. */
open class StorageException(message: String) : Exception(message)
