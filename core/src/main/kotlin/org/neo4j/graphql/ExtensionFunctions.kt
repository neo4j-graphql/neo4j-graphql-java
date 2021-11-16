package org.neo4j.graphql

import graphql.language.Description
import graphql.language.VariableReference
import graphql.schema.GraphQLOutputType
import org.neo4j.cypherdsl.core.*
import java.util.*

fun queryParameter(value: Any?, vararg parts: String?): Parameter<*> {
    val name = when (value) {
        is VariableReference -> value.name
        else -> normalizeName(*parts)
    }
    return org.neo4j.cypherdsl.core.Cypher.parameter(name).withValue(value?.toJavaValue())
}

fun Expression.collect(type: GraphQLOutputType) = if (type.isList()) Functions.collect(this) else this
fun StatementBuilder.OngoingReading.withSubQueries(subQueries: List<Statement>) = subQueries.fold(this, { it, sub -> it.call(sub) })

fun normalizeName(vararg parts: String?) = parts.mapNotNull { it?.capitalize() }.filter { it.isNotBlank() }.joinToString("").decapitalize()

fun PropertyContainer.id(): FunctionInvocation = when (this) {
    is Node -> Functions.id(this)
    is Relationship -> Functions.id(this)
    else -> throw IllegalArgumentException("Id can only be retrieved for Nodes or Relationships")
}

fun String.toCamelCase(): String = Regex("[\\W_]([a-z])").replace(this) { it.groupValues[1].toUpperCase() }

fun <T> Optional<T>.unwrap(): T? = orElse(null)

fun String.asDescription() = Description(this, null, this.contains("\n"))

fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
fun String.decapitalize(): String = replaceFirstChar { it.lowercase(Locale.getDefault()) }
fun String.toUpperCase(): String = uppercase(Locale.getDefault())
fun String.toLowerCase(): String = lowercase(Locale.getDefault())
