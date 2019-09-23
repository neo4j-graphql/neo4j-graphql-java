package org.neo4j.graphql

import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLType
import java.io.PrintWriter
import java.io.StringWriter

fun Throwable.stackTraceAsString(): String {
    val sw = StringWriter()
    this.printStackTrace(PrintWriter(sw))
    return sw.toString()
}

fun <T> Iterable<T>.joinNonEmpty(separator: CharSequence = ", ", prefix: CharSequence = "", postfix: CharSequence = "", limit: Int = -1, truncated: CharSequence = "...", transform: ((T) -> CharSequence)? = null): String {
    return if (iterator().hasNext()) joinTo(StringBuilder(), separator, prefix, postfix, limit, truncated, transform).toString() else ""
}

fun input(name: String, type: GraphQLType): GraphQLArgument {
    return GraphQLArgument
        .newArgument()
        .name(name)
        .type((type.ref() as? GraphQLInputType)
                ?: throw IllegalArgumentException("${type.innerName()} is not allowed for input")).build()
}