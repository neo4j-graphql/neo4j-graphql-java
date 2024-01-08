package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.cypherdsl.core.Property
import org.neo4j.cypherdsl.core.PropertyContainer
import org.neo4j.graphql.Constants
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.directives.AuthDirective.AuthOperation
import org.neo4j.graphql.domain.directives.TimestampDirective.TimeStampOperation
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.name
import org.neo4j.graphql.schema.model.inputs.ScalarProperties

//TODO complete
enum class Operation(val auth: AuthOperation, val timestamp: TimeStampOperation) {
    CREATE(AuthOperation.CREATE, TimeStampOperation.CREATE),
    UPDATE(AuthOperation.UPDATE, TimeStampOperation.UPDATE),
}

fun createSetProperties(
    propertyContainer: PropertyContainer,
    properties: ScalarProperties?,
    operation: Operation,
    fieldContainer: FieldContainer<*>?,
    schemaConfig: SchemaConfig,
    context: QueryContext,
    paramPrefix: ChainString? = null,
    extendParamWithName: Boolean = true, // TODO this is ´only to align with JS
    autoGenerateFieldFilter: ((BaseField) -> Boolean)? = null
): List<Expression>? = createSetPropertiesSplit(
    propertyContainer,
    properties,
    operation,
    fieldContainer,
    schemaConfig,
    context,
    paramPrefix,
    extendParamWithName,
    autoGenerateFieldFilter
)
    ?.flatMap { listOf(it.first, it.second) }

fun createSetPropertiesSplit(
    propertyContainer: PropertyContainer,
    properties: ScalarProperties?,
    operation: Operation,
    fieldContainer: FieldContainer<*>?,
    schemaConfig: SchemaConfig,
    context: QueryContext,
    paramPrefix: ChainString? = null,
    extendParamWithName: Boolean = true, // TODO this is ´only to align with JS
    autoGenerateFieldFilter: ((BaseField) -> Boolean)? = null
): List<Pair<Property, Expression>>? {
    if (fieldContainer == null) return null
    return createSetPropertiesSplit(
        propertyContainer,
        properties?.map { (field, value) ->
            val param = when {
                extendParamWithName -> paramPrefix
                    ?.extend(field)
                    ?.resolveParameter(value)
                    ?: context.getNextParam(value)

                else -> context.getNextParam(paramPrefix, value)
            }
            field to param
        },
        operation, fieldContainer, autoGenerateFieldFilter
    )
}


fun createSetPropertiesSplit(
    propertyContainer: PropertyContainer,
    properties: List<Pair<ScalarField, Expression>>?,
    operation: Operation,
    fieldContainer: FieldContainer<*>?,
    autoGenerateFieldFilter: ((BaseField) -> Boolean)? = null
): List<Pair<Property, Expression>>? {
    if (fieldContainer == null) return null

    val expressions = mutableListOf<Pair<Property, Expression>>()

    fun set(field: BaseField, expression: Expression) {
        expressions += propertyContainer.property(field.dbPropertyName) to expression
    }

    fieldContainer.temporalFields
        .filter { it.timestamps?.contains(operation.timestamp) == true }
        .forEach {
            when (it.typeMeta.type.name()) {
                Constants.DATE_TIME -> set(it, Functions.datetime())
                Constants.TIME -> set(it, Functions.time())
            }
        }

    if (operation == Operation.CREATE) {
        fieldContainer.primitiveFields
            .filter { it.autogenerate }
            .filter { autoGenerateFieldFilter?.invoke(it) ?: true }
            .forEach { set(it, Functions.randomUUID()) }

    }

    properties?.forEach { (field, value) ->
        val valueToSet = field.convertInputToCypher(value)
        set(field, valueToSet)
    }
    return expressions.takeIf { it.isNotEmpty() }
}
