package org.neo4j.cypherdsl.core

import java.util.*

@Deprecated(message = "This is a shortcut to use the old generator logic in combination with this DSL, remove as soon DSL is used everywhere")
class PassThrough(content: String) : Literal<String>(content) {
    override fun asString(): String {
        return Objects.requireNonNull(content)
    }
}
