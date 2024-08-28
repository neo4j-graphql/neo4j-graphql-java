package org.neo4j.graphql

import graphql.language.*
import graphql.schema.GraphQLOutputType
import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.Node
import org.neo4j.cypherdsl.core.StatementBuilder.*
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.fields.RelationDeclarationField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.options.OptionsInput
import org.neo4j.graphql.translate.ApocFunctions
import org.neo4j.graphql.translate.ApocFunctions.UtilFunctions.callApocValidate
import org.neo4j.graphql.translate.connection_clause.CreateConnectionClause
import org.neo4j.graphql.utils.IResolveTree
import java.util.*

@Deprecated("use ChainString")
fun queryParameter(value: Any?, vararg parts: String?): Parameter<*> {
    val name = when (value) {
        is VariableReference -> value.name
        else -> normalizeName2(*parts)
    }
    return org.neo4j.cypherdsl.core.Cypher.parameter(name).withValue(value?.toJavaValue())
}

fun Expression.collect(type: GraphQLOutputType) = if (type.isList()) Cypher.collect(this) else this
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
    is Node -> Cypher.elementId(this)
    is Relationship -> Cypher.elementId(this)
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

fun Collection<Condition?>.foldWithOr(): Condition? = this
    .filterNotNull()
    .takeIf { it.isNotEmpty() }
    ?.let { conditions ->
        var result = Cypher.noCondition()
        conditions.forEach {
            result = result or it
        }
        result
    }

fun ExposesCall<OngoingInQueryCallWithoutArguments>.apocValidate(cond: Condition, errorMessage: String): VoidCall =
    this.callApocValidate(cond.not(), errorMessage.asCypherLiteral(), Cypher.listOf(0.asCypherLiteral()))

fun Condition?.apocValidatePredicate(errorMessage: String = Constants.AUTH_FORBIDDEN_ERROR) = this?.let {
    ApocFunctions.util.validatePredicate(it.not(), errorMessage.asCypherLiteral(), Cypher.listOf(0.asCypherLiteral()))
        .asCondition()
}

// TODO rename
fun ExposesReturning.apocValidateNew(cond: Condition?, errorMessage: String) =
    if (cond == null) {
        this
    } else {
        when (this) {
//            is OngoingReading -> this
            is ExposesWith -> this.with(Cypher.asterisk())
            else -> error("cannot handle " + this::class.java.name)
        }.apocValidate(cond, errorMessage)
    }


fun Named.name(): String = this.requiredSymbolicName.value

fun OngoingReading.applySortingSkipAndLimit(
    orderAndLimit: CreateConnectionClause.OrderAndLimit?,
    queryContext: QueryContext,
    prefix: ChainString? = null,
    withVars: List<Named>? = null,
): OngoingReading {
    if (orderAndLimit == null) {
        return this
    }
    val ordered = orderAndLimit.sortItems
        .takeIf { it.isNotEmpty() }
        ?.let {
            if (this is ExposesOrderBy) {
                this
            } else {
                if (withVars != null) {
                    this.with(*withVars.toTypedArray())
                } else {
                    this.with(Cypher.asterisk())
                }
            }.orderBy(it)
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
        }.skip(queryContext.getNextParam(prefix, it))
    }
        ?: ordered
    return orderAndLimit.limit?.let {
        if (skip is ExposesLimit) {
            skip
        } else {
            if (withVars != null) {
                this.with(*withVars.toTypedArray())
            } else {
                skip.with(Cypher.asterisk())
            }
        }.limit(queryContext.getNextParam(prefix, it))
    }
        ?: skip
}

fun OngoingReading.applySortingSkipAndLimit(
    p: PropertyContainer,
    optionsInput: OptionsInput,
    sortFields: Map<String, Expression>,
    queryContext: QueryContext,
): OngoingReading {
    return this.applySortingSkipAndLimit(CreateConnectionClause.OrderAndLimit(
        optionsInput.sort
            ?.flatMap { it.getCypherSortFields(p::property, sortFields) }
            ?: emptyList(),
        optionsInput.offset,
        optionsInput.limit
    ), queryContext)
}

fun StatementBuilder.OngoingReadingAndReturn.applySortingSkipAndLimit(
    p: PropertyContainer,
    optionsInput: OptionsInput,
    schemaConfig: SchemaConfig
): BuildableStatement<ResultStatement> {
    val ordered = optionsInput.sort
        ?.flatMap { it.map { (field, direction) -> Cypher.sort(p.property(field), direction) } }
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

fun ExposesWith.maybeWithAsterix() = when (this) {
    is OngoingReading -> this
    else -> this.with(Cypher.asterisk())
}

fun ExposesWith.maybeWith(withVars: List<SymbolicName>) = when (this) {
    is OngoingReading -> this
    else -> this.with(withVars)
}

fun ExposesWith.requiresExposeSet(withVars: List<SymbolicName>): ExposesSet = when (this) {
    is ExposesSet -> this
    else -> this.with(withVars)
}

fun Any?.nestedMap(vararg path: String): Map<String, *>? =
    (nestedObject(*path) as? Map<*, *>)?.mapKeys { it.key as String }

fun Any?.nestedObject(vararg path: String, pos: Int = 0): Any? {
    if (pos == path.size) {
        return this
    }
    return (this as? Map<*, *>)?.get(path[pos])?.let { it.nestedObject(*path, pos = pos + 1) }
}

inline fun <reified T> T.wrapList(): List<T> = when (this) {
    is List<*> -> this
        .filterNotNull()
        .filterIsInstance<T>()
        .also { check(it.size == this.size, { "expected only elements of type " + T::class.java }) }

    else -> listOf(this)
}

fun <X, T : X, R : X> T.conditionalBlock(cond: Boolean, block: (T) -> R): X =
    if (cond) block(this) else this

fun OngoingReadingWithoutWhere.optionalWhere(condition: List<Condition?>): OngoingReading =
    optionalWhere(condition.filterNotNull().takeIf { it.isNotEmpty() }?.foldWithAnd())

fun OngoingReadingWithoutWhere.optionalWhere(condition: Condition?): OngoingReading =
    if (condition != null) this.where(condition) else this

fun OrderableOngoingReadingAndWithWithoutWhere.optionalWhere(condition: Condition?): OngoingReading =
    if (condition != null) this.where(condition) else this

fun OrderableOngoingReadingAndWithWithoutWhere.optionalWhere(condition: List<Condition?>): OngoingReading =
    optionalWhere(condition.filterNotNull().takeIf { it.isNotEmpty() }?.foldWithAnd())

fun Iterable<IResolveTree>.project(expression: Expression): List<Any> = this.flatMap {
    listOf(it.aliasOrName, expression)
}

fun Any?.toDict() = Dict.create(this) ?: Dict.EMPTY
fun Iterable<Any?>.toDict(): List<Dict> = this.mapNotNull { Dict.create(it) }

fun String.toDeprecatedDirective() = Directive("deprecated", listOf(Argument("reason", StringValue(this))))


fun List<Interface>.getFieldDeclaringRelationship(fieldName: String): RelationDeclarationField? {
    return this.firstNotNullOfOrNull { interfaze ->
        // first look at parent interfaces
        interfaze.interfaces.getFieldDeclaringRelationship(fieldName)
        // then look at the current interface
            ?: (interfaze.getField(fieldName) as? RelationDeclarationField)?.root
    }
}

