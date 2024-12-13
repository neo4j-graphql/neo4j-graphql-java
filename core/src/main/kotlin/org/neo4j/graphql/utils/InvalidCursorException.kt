package org.neo4j.graphql.utils

class InvalidCursorException internal constructor(message: String?, cause: Throwable? = null) :
    RuntimeException(message, cause)
