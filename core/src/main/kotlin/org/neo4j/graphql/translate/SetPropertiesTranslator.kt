package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.PropertyContainer
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.directives.AuthDirective.AuthOperation
import org.neo4j.graphql.domain.directives.TimestampDirective.TimeStampOperation
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.schema.model.inputs.ScalarProperties

//TODO complete
private enum class Operation(val auth: AuthOperation, val timestamp: TimeStampOperation) {
    CREATE(AuthOperation.CREATE, TimeStampOperation.CREATE),
    UPDATE(AuthOperation.UPDATE, TimeStampOperation.UPDATE),
}

data class SetPropertiesResult(
    val expressions: List<Expression>,
    val preConditions: Condition? = null,
)

fun createSetPropertiesOnCreate(
    propertyContainer: PropertyContainer,
    properties: ScalarProperties?,
    fieldContainer: FieldContainer<*>?,
    context: QueryContext,
    autoGenerateFieldFilter: ((BaseField) -> Boolean)? = null
): List<Expression>? = createSetProperties(
    propertyContainer,
    properties,
    Operation.CREATE,
    fieldContainer,
    context,
    autoGenerateFieldFilter
)?.expressions

fun createSetPropertiesOnUpdate(
    propertyContainer: PropertyContainer,
    properties: ScalarProperties?,
    fieldContainer: FieldContainer<*>?,
    context: QueryContext,
    autoGenerateFieldFilter: ((BaseField) -> Boolean)? = null
) = createSetProperties(
    propertyContainer,
    properties,
    Operation.UPDATE,
    fieldContainer,
    context,
    autoGenerateFieldFilter
)


private fun createSetProperties(
    propertyContainer: PropertyContainer,
    properties: ScalarProperties?,
    operation: Operation,
    fieldContainer: FieldContainer<*>?,
    context: QueryContext,
    autoGenerateFieldFilter: ((BaseField) -> Boolean)? = null
): SetPropertiesResult? {
    if (fieldContainer == null) return null

    val nonNullFields = mutableSetOf<ScalarField>()
    val updateExpressions = properties?.map { (field, updates) ->
        check(
            updates.size <= 1,
            { "Cannot mutate the same field multiple times in one Mutation: ${field.dbPropertyName}" })
        val update = updates.first()
        val param = context.getNextParam(update.value)
        val exp = when (update.operation) {
            ScalarField.ScalarUpdateOperation.POP -> {
                nonNullFields += field
                Cypher.subList(
                    propertyContainer.property(field.dbPropertyName),
                    0.asCypherLiteral(),
                    Cypher.minus(param)
                )
            }

            ScalarField.ScalarUpdateOperation.PUSH -> {
                nonNullFields += field
                Cypher.add(propertyContainer.property(field.dbPropertyName), field.convertInputToCypher(param))
            }

            else -> field.convertInputToCypher(param)
        }
        field to exp

    }
    val expressions = createInternaleSetPropertiesSplit(
        propertyContainer,
        updateExpressions,
        operation,
        fieldContainer,
        autoGenerateFieldFilter
    ) ?: return null


    val preConditions = if (nonNullFields.isNotEmpty()) {
        val nullChecks = nonNullFields.fold(Cypher.noCondition()) { condition, field ->
            condition or propertyContainer.property(field.dbPropertyName).isNull
        }

        val propertyNames = nonNullFields.map { it.dbPropertyName.asCypherLiteral() }
        val errorMessage =
            "Propert${if (propertyNames.size > 1) "ies" else "y"} ${propertyNames.joinToString(", ") { "%s" }} cannot be NULL"

        ApocFunctions.util.validatePredicate(
            nullChecks,
            errorMessage.asCypherLiteral(),
            Cypher.listOf(propertyNames)
        ).asCondition()
    } else {
        null
    }
    return SetPropertiesResult(expressions, preConditions)
}

fun createSetPropertiesOnCreate(
    propertyContainer: PropertyContainer,
    properties: List<Pair<ScalarField, Expression>>?,
    fieldContainer: FieldContainer<*>?,
    autoGenerateFieldFilter: ((BaseField) -> Boolean)? = null
) = createInternaleSetPropertiesSplit(
    propertyContainer,
    properties?.map { (field, value) -> field to field.convertInputToCypher(value) },
    Operation.CREATE,
    fieldContainer,
    autoGenerateFieldFilter
)

private fun createInternaleSetPropertiesSplit(
    propertyContainer: PropertyContainer,
    properties: List<Pair<ScalarField, Expression>>?,
    operation: Operation,
    fieldContainer: FieldContainer<*>?,
    autoGenerateFieldFilter: ((BaseField) -> Boolean)? = null
): List<Expression>? {
    if (fieldContainer == null) return null

    val expressions = mutableListOf<Expression>()

    fun set(field: BaseField, expression: Expression) {
        expressions += propertyContainer.property(field.dbPropertyName).to(expression)
    }

    fieldContainer.temporalFields
        .filter { it.timestamps?.contains(operation.timestamp) == true }
        .forEach {
            when (it.typeMeta.type.name()) {
                Constants.DATE_TIME -> set(it, Cypher.datetime())
                Constants.TIME -> set(it, Cypher.time())
            }
        }

    if (operation == Operation.CREATE) {
        fieldContainer.primitiveFields
            .filter { it.autogenerate }
            .filter { autoGenerateFieldFilter?.invoke(it) ?: true }
            .forEach { set(it, Cypher.randomUUID()) }
    }

    properties?.forEach { (field, value) -> set(field, value) }
    return expressions.takeIf { it.isNotEmpty() }
}
