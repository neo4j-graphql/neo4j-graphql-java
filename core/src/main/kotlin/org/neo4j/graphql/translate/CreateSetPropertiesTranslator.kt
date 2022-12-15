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
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.PointField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.isList
import org.neo4j.graphql.name

//TODO complete
object CreateSetPropertiesTranslator {

    enum class Operation(val auth: AuthOperation, val timestamp: TimeStampOperation) {
        CREATE(AuthOperation.CREATE, TimeStampOperation.CREATE),
        UPDATE(AuthOperation.UPDATE, TimeStampOperation.UPDATE),
    }

    fun createSetProperties(
        propertyContainer: PropertyContainer,
        properties: Map<*, *>?,
        operation: Operation,
        fieldContainer: FieldContainer<*>,
        schemaConfig: SchemaConfig,
        paramPrefix: ChainString? = null,
    ): Collection<Expression> {

        val expressions = mutableListOf<Expression>()

        fun set(field: BaseField, expression: Expression) {
            expressions += propertyContainer.property(field.dbPropertyName)
            expressions += expression
        }

        if (operation == Operation.CREATE) {
            fieldContainer.primitiveFields
                .filter { it.autogenerate }
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

        properties?.forEach { (fieldName, value) ->
            // only handle scalar fields here

            // TODO why only scalars and not MutableField
            val field = fieldContainer.getField(fieldName as String) as? ScalarField ?: return@forEach

            val param = (paramPrefix ?: ChainString(schemaConfig, propertyContainer))
                .extend(fieldName)
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
        return expressions
    }

}
