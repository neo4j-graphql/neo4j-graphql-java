package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.cypherdsl.core.PropertyContainer
import org.neo4j.graphql.Constants
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.directives.AuthDirective.AuthOperation
import org.neo4j.graphql.domain.directives.TimestampDirective.TimeStampOperation
import org.neo4j.graphql.domain.inputs.ScalarProperties
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.PointField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.isList
import org.neo4j.graphql.name

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
    paramPrefix: ChainString? = null,
    autoGenerateFieldFilter: ((BaseField) -> Boolean)? = null
): List<Expression>? {
    if (fieldContainer == null) return null

    val expressions = mutableListOf<Expression>()

    fun set(field: BaseField, expression: Expression) {
        expressions += propertyContainer.property(field.dbPropertyName)
        expressions += expression
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
        val param = (paramPrefix ?: ChainString(schemaConfig, propertyContainer))
            .extend(field)
            .resolveParameter(value)

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

