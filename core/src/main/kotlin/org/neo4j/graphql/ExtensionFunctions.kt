package org.neo4j.graphql

import graphql.language.VariableReference
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLType
import org.neo4j.cypherdsl.core.*

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

fun queryParameter(value: Any?, vararg parts: String?): Parameter<Any> {
    val name = when (value) {
        is VariableReference -> value.name
        else -> normalizeName(*parts)
    }
    return org.neo4j.cypherdsl.core.Cypher.parameter(name).withValue(value?.toJavaValue())
}

//fun normalizeName(vararg parts: String?) = parts.mapNotNull { it?.capitalize() }.filter { it.isNotBlank() }.joinToString("").decapitalize()
fun normalizeName(vararg parts: String?) = parts.filterNot { it.isNullOrBlank() }.joinToString("_")

fun PropertyContainer.id(): FunctionInvocation = when (this) {
    is Node -> Functions.id(this)
    is Relationship -> Functions.id(this)
    else -> throw IllegalArgumentException("Id can only be retrieved for Nodes or Relationships")
}

fun String.isJavaIdentifier() =
        this[0].isJavaIdentifierStart() &&
                this.substring(1).all { it.isJavaIdentifierPart() }

fun String.quote() = if (isJavaIdentifier()) this else "`$this`"
