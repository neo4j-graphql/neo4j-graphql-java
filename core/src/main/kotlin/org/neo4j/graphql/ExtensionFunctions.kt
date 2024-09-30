package org.neo4j.graphql

import graphql.language.Argument
import graphql.language.Description
import graphql.language.Directive
import graphql.language.StringValue
import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.*
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionSort
import org.neo4j.graphql.schema.model.inputs.options.OptionsInput
import org.neo4j.graphql.schema.model.inputs.options.SortInput
import java.util.*

fun OngoingReading.withSubQueries(subQueries: List<Statement>) =
    subQueries.fold(this, { it, sub -> it.call(sub) })

fun String.toCamelCase(): String = Regex("[\\W_]([a-z])").replace(this) { it.groupValues[1].toUpperCase() }

fun <T> Optional<T>.unwrap(): T? = orElse(null)

fun String.asDescription() = Description(this, null, this.contains("\n"))

fun String.capitalize(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

fun String.decapitalize(): String = replaceFirstChar { it.lowercase(Locale.getDefault()) }
fun String.toUpperCase(): String = uppercase(Locale.getDefault())
fun String.toLowerCase(): String = lowercase(Locale.getDefault())

infix fun Condition?.and(rhs: Condition) = this?.and(rhs) ?: rhs
infix fun Condition?.or(rhs: Condition) = this?.or(rhs) ?: rhs
fun Collection<Condition?>.foldWithAnd(): Condition? = this
    .filterNotNull()
    .takeIf { it.isNotEmpty() }
    ?.let { conditions ->
        var result = Cypher.noCondition()
        conditions.forEach {
            result = result and it
        }
        result
    }

fun Named.name(): String = this.requiredSymbolicName.value

fun <T> OngoingReading.applySortingSkipAndLimit(
    orderAndLimit: OptionsInput<T>?,
    extractSortFields: (T) -> Collection<SortItem>,
    queryContext: QueryContext,
    withVars: List<IdentifiableElement>? = null,
    enforceAsterix: Boolean = false,
): OngoingReading {
    var asterixEnforced = false
    if (orderAndLimit == null || orderAndLimit.isEmpty()) {
        return this
    }
    val ordered = orderAndLimit.sort
        .takeIf { it.isNotEmpty() }
        ?.let { sortItem ->
            if (this is ExposesOrderBy) {
                if (enforceAsterix) {
                    asterixEnforced = true
                    this.with(Cypher.asterisk())
                } else {
                    this
                }
            } else {
                if (withVars != null) {
                    this.with(*withVars.toTypedArray())
                } else {
                    this.with(Cypher.asterisk())
                }
            }.orderBy(sortItem.flatMap { extractSortFields(it) })
        }
        ?: this
    val skip = orderAndLimit.offset?.let {
        if (ordered is ExposesSkip) {
            ordered
        } else {
            if (withVars != null) {
                this.with(*withVars.toTypedArray())
            } else {
                ordered.with(Cypher.asterisk())
            }
        }.skip(queryContext.getNextParam(it))
    }
        ?: ordered
    return orderAndLimit.limit?.let {
        if (skip is ExposesLimit) {
            if (enforceAsterix && !asterixEnforced) {
                skip.with(Cypher.asterisk())
            } else {
                skip
            }
        } else {
            if (withVars != null) {
                this.with(*withVars.toTypedArray())
            } else {
                skip.with(Cypher.asterisk())
            }
        }.limit(queryContext.getNextParam(it))
    }
        ?: skip
}

fun OngoingReading.applySortingSkipAndLimit(
    p: PropertyAccessor,
    optionsInput: OptionsInput<SortInput>?,
    queryContext: QueryContext,
    withVars: List<IdentifiableElement>? = null,
    enforceAsterix: Boolean = false,
    alreadyProjected: Boolean = false,
): OngoingReading {
    return this.applySortingSkipAndLimit(
        optionsInput,
        { it.getCypherSortFields(p::property, alreadyProjected) },
        queryContext,
        withVars = withVars,
        enforceAsterix = enforceAsterix
    )
}

fun OngoingReading.applySortingSkipAndLimit(
    optionsInput: OptionsInput<ConnectionSort>?,
    node: PropertyAccessor,
    edge: PropertyAccessor,
    queryContext: QueryContext,
    withVars: List<IdentifiableElement>? = null,
    enforceAsterix: Boolean = false,
    alreadyProjected: Boolean = false,
): OngoingReading {
    return this.applySortingSkipAndLimit(
        optionsInput,
        {
            listOf(
                it.node?.getCypherSortFields(node::property, alreadyProjected),
                it.edge?.getCypherSortFields(edge::property, alreadyProjected),
            )
                .filterNotNull()
                .flatten()
        },
        queryContext,
        withVars = withVars,
        enforceAsterix = enforceAsterix
    )
}


inline fun <reified T> T.wrapList(): List<T> = when (this) {
    is List<*> -> this
        .filterNotNull()
        .filterIsInstance<T>()
        .also { check(it.size == this.size, { "expected only elements of type " + T::class.java }) }

    else -> listOf(this)
}

fun OngoingReadingWithoutWhere.optionalWhere(condition: List<Condition?>): OngoingReading =
    optionalWhere(condition.filterNotNull().takeIf { it.isNotEmpty() }?.foldWithAnd())

fun OngoingReadingWithoutWhere.optionalWhere(condition: Condition?): OngoingReading =
    if (condition != null) this.where(condition) else this

fun OrderableOngoingReadingAndWithWithoutWhere.optionalWhere(condition: Condition?): OngoingReading =
    if (condition != null) this.where(condition) else this

fun Any?.toDict() = Dict.create(this) ?: Dict.EMPTY
fun Iterable<Any?>.toDict(): List<Dict> = this.mapNotNull { Dict.create(it) }

fun String.toDeprecatedDirective() = Directive("deprecated", listOf(Argument("reason", StringValue(this))))

