package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.directives.AuthDirective.AuthOperation
import org.neo4j.graphql.domain.directives.TimestampDirective.TimeStampOperation
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.PointField
import org.neo4j.graphql.handler.utils.ChainString
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
    autoGenerateFieldFilter: ((BaseField) -> Boolean)? = null,
): List<Expression>? = createSetPropertiesSplit(
    propertyContainer,
    properties,
    operation,
    fieldContainer,
    schemaConfig,
    context,
    paramPrefix,
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
    autoGenerateFieldFilter: ((BaseField) -> Boolean)? = null,
): List<Pair<Property, Expression>>? {
    if (fieldContainer == null) return null

    val expressions = mutableListOf<Pair<Property, Expression>>()

    fun set(field: BaseField, expression: Expression) {
        expressions += propertyContainer.property(field.dbPropertyName) to expression
    }

    if (operation == Operation.CREATE) {
        fieldContainer.primitiveFields
            .filter { it.autogenerate }
            .filter { autoGenerateFieldFilter?.invoke(it) ?: true }
            .forEach { set(it, Functions.randomUUID()) }

    }

    fieldContainer.temporalFields
        .filter { it.timestamps?.contains(operation.timestamp) == true }
        .forEach {
            when (it.typeMeta.type.name()) {
                Constants.DATE_TIME -> set(it, Functions.datetime())
                Constants.TIME -> set(it, Functions.time())
            }
        }

    properties?.forEach { (field, value) ->
        // TODO use only params without prefix?
        val param = paramPrefix?.extend(field)?.resolveParameter(value) ?: context.getNextParam(value)

        val valueToSet = when (field) {
            is PointField ->
                if (field.typeMeta.type.isList()) {
                    val point = Cypher.name("p")
                    Cypher.listWith(point).`in`(param).returning(Functions.point(point))
                } else {
                    Functions.point(param)
                }

            else -> param
        }
        set(field, valueToSet)
    }
    return expressions.takeIf { it.isNotEmpty() }
}

