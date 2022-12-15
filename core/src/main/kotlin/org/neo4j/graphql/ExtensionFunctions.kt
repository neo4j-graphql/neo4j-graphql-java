package org.neo4j.graphql

import graphql.language.Description
import graphql.language.VariableReference
import graphql.schema.GraphQLOutputType
import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.*
import org.neo4j.graphql.domain.dto.OptionsInput
import java.util.*

@Deprecated("use ChainString")
fun queryParameter(value: Any?, vararg parts: String?): Parameter<*> {
    val name = when (value) {
        is VariableReference -> value.name
        else -> normalizeName2(*parts)
    }
    return org.neo4j.cypherdsl.core.Cypher.parameter(name).withValue(value?.toJavaValue())
}

fun Expression.collect(type: GraphQLOutputType) = if (type.isList()) Functions.collect(this) else this
fun OngoingReading.withSubQueries(subQueries: List<Statement>?) =
    subQueries?.fold(this, { it, sub -> it.call(sub) }) ?: this

fun ExposesSubqueryCall.withSubQueries(subQueries: List<Statement>?) =
    subQueries?.fold(this, { it, sub -> it.call(sub) }) ?: this

@Deprecated("use ChainString")
fun normalizeName(vararg parts: String?) =
    parts.mapNotNull { it?.capitalize() }.filter { it.isNotBlank() }.joinToString("").decapitalize()

@Deprecated("use ChainString")
fun normalizeName2(vararg parts: String?) = parts.filter { it?.isNotBlank() == true }.joinToString("_")

fun PropertyContainer.id(): FunctionInvocation = when (this) {
    is Node -> Functions.id(this)
    is Relationship -> Functions.id(this)
    else -> throw IllegalArgumentException("Id can only be retrieved for Nodes or Relationships")
}

fun String.toCamelCase(): String = Regex("[\\W_]([a-z])").replace(this) { it.groupValues[1].toUpperCase() }

fun <T> Optional<T>.unwrap(): T? = orElse(null)

fun String.asDescription() = Description(this, null, this.contains("\n"))

fun String.capitalize(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

fun String.decapitalize(): String = replaceFirstChar { it.lowercase(Locale.getDefault()) }
fun String.toUpperCase(): String = uppercase(Locale.getDefault())
fun String.toLowerCase(): String = lowercase(Locale.getDefault())

fun <E> Collection<E>.containsAny(other: Collection<E>): Boolean = this.find { other.contains(it) } != null
infix fun Condition?.and(rhs: Condition) = this?.and(rhs) ?: rhs
infix fun Condition?.or(rhs: Condition) = this?.or(rhs) ?: rhs

fun ExposesCall<OngoingInQueryCallWithoutArguments>.apocValidate(cond: Condition, errorMessage: String): VoidCall =
    this.call("apoc.util.validate")
        .withArgs(cond.not(), errorMessage.asCypherLiteral(), Cypher.listOf(0.asCypherLiteral()))
        .withoutResults()

fun Condition?.apocValidatePredicate(errorMessage: String) = this?.let {
    Cypher.call("apoc.util.validatePredicate")
        .withArgs(it.not(), errorMessage.asCypherLiteral(), Cypher.listOf(0.asCypherLiteral()))
        .asFunction()
        .asCondition()
}

fun Named.name(): String = this.requiredSymbolicName.value

fun StatementBuilder.OngoingReadingAndReturn.applySortingSkipAndLimit(
    p: PropertyContainer,
    optionsInput: OptionsInput,
    schemaConfig: SchemaConfig
): BuildableStatement<ResultStatement> {
    val ordered = optionsInput.sort
        ?.map { (field, direction) -> Cypher.sort(p.property(field), direction) }
        ?.takeIf { it.isNotEmpty() }
        ?.let { this.orderBy(it) }
        ?: this
    val skip = optionsInput.offset
        ?.let { ordered.skip(Cypher.parameter(schemaConfig.namingStrategy.resolveParameter(p.name(), "offset"), it)) }
        ?: ordered
    return optionsInput.limit
        ?.let { ordered.limit(Cypher.parameter(schemaConfig.namingStrategy.resolveParameter(p.name(), "limit"), it)) }
        ?: skip
}

fun StatementBuilder.ExposesWith.maybeWith(withVars: List<SymbolicName>) = when (this) {
    is OngoingReading -> this
    else -> this.with(withVars)
}

fun StatementBuilder.ExposesWith.requiresExposeSet(withVars: List<SymbolicName>): ExposesSet = when (this) {
    is ExposesSet -> this
    else -> this.with(withVars)
}

fun Any?.nestedMap(vararg path: String): Map<*, *>? = nestedObject(*path) as? Map<*, *>
fun Any?.nestedObject(vararg path: String, pos: Int = 0): Any? {
    if (pos == path.size) {
        return this
    }
    return (this as? Map<*, *>)?.get(path[pos])?.let { it.nestedObject(*path, pos = pos + 1) }
}
