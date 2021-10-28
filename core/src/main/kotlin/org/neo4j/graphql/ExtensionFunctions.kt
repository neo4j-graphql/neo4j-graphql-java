package org.neo4j.graphql

import graphql.language.Description
import graphql.language.VariableReference
import org.neo4j.cypherdsl.core.*
import java.util.*

fun <T> Iterable<T>.joinNonEmpty(separator: CharSequence = ", ", prefix: CharSequence = "", postfix: CharSequence = "", limit: Int = -1, truncated: CharSequence = "...", transform: ((T) -> CharSequence)? = null): String {
    return if (iterator().hasNext()) joinTo(StringBuilder(), separator, prefix, postfix, limit, truncated, transform).toString() else ""
}

fun queryParameter(value: Any?, vararg parts: String?): Parameter<*> {
    val name = when (value) {
        is VariableReference -> value.name
        else -> normalizeName(*parts)
    }
    return org.neo4j.cypherdsl.core.Cypher.parameter(name).withValue(value?.toJavaValue())
}

fun normalizeName(vararg parts: String?) = parts.mapNotNull { it?.capitalize() }.filter { it.isNotBlank() }.joinToString("").decapitalize()
//fun normalizeName(vararg parts: String?) = parts.filterNot { it.isNullOrBlank() }.joinToString("_")

fun PropertyContainer.id(): FunctionInvocation = when (this) {
    is Node -> Functions.id(this)
    is Relationship -> Functions.id(this)
    else -> throw IllegalArgumentException("Id can only be retrieved for Nodes or Relationships")
}

fun String.toCamelCase(): String = Regex("[\\W_]([a-z])").replace(this) { it.groupValues[1].toUpperCase() }

fun <T> Optional<T>.unwrap(): T? = orElse(null)

fun String.asDescription() = Description(this, null, this.contains("\n"))
