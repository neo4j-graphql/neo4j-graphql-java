package org.neo4j.graphql

import graphql.schema.GraphQLFieldDefinition
import graphql.schema.SelectedField
import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.graphql.handler.BaseDataFetcherForContainer

private const val NEO4j_FORMATTED_PROPERTY_KEY = "formatted"
const val NEO4j_POINT_DISTANCE_FILTER = "_Neo4jPointDistanceFilter"
const val NEO4j_POINT_DISTANCE_FILTER_SUFFIX = "_distance"

data class TypeDefinition(
        val name: String,
        val typeDefinition: String,
        val inputDefinition: String = typeDefinition + "Input"
)

class Neo4jTemporalConverter(name: String) : Neo4jSimpleConverter(name) {
    override fun projectField(variable: SymbolicName, field: SelectedField, name: String): Any {
        return Cypher.call("toString").withArgs(variable.property(field.name)).asFunction()
    }

    override fun createCondition(property: Property, parameter: Parameter<*>, conditionCreator: (Expression, Expression) -> Condition): Condition {
        return conditionCreator(property, toExpression(parameter))
    }
}

class Neo4jTimeConverter(name: String) : Neo4jConverter(name) {

    override fun createCondition(
            fieldName: String,
            field: GraphQLFieldDefinition,
            parameter: Parameter<*>,
            conditionCreator: (Expression, Expression) -> Condition,
            propertyContainer: PropertyContainer
    ): Condition = if (fieldName == NEO4j_FORMATTED_PROPERTY_KEY) {
        val exp = toExpression(parameter)
        conditionCreator(propertyContainer.property(field.name), exp)
    } else {
        super.createCondition(fieldName, field, parameter, conditionCreator, propertyContainer)
    }

    override fun projectField(variable: SymbolicName, field: SelectedField, name: String): Any = when (name) {
        NEO4j_FORMATTED_PROPERTY_KEY -> Cypher.call("toString").withArgs(variable.property(field.name)).asFunction()
        else -> super.projectField(variable, field, name)
    }

    override fun getMutationExpression(value: Any, field: GraphQLFieldDefinition): BaseDataFetcherForContainer.PropertyAccessor {
        val fieldName = field.name
        return (value as? Map<*, *>)
            ?.get(NEO4j_FORMATTED_PROPERTY_KEY)
            ?.let {
                BaseDataFetcherForContainer.PropertyAccessor(fieldName) { variable ->
                    val param = queryParameter(value, variable, fieldName)
                    toExpression(param.property(NEO4j_FORMATTED_PROPERTY_KEY))
                }
            }
                ?: super.getMutationExpression(value, field)
    }
}

class Neo4jPointConverter(name: String) : Neo4jConverter(name) {

    fun createDistanceCondition(lhs: Expression, rhs: Parameter<*>, conditionCreator: (Expression, Expression) -> Condition): Condition {
        val point = Functions.point(rhs.property("point"))
        val distance = rhs.property("distance")
        return conditionCreator(Functions.distance(lhs, point), distance)
    }
}

open class Neo4jConverter(
        name: String,
        val prefixedName: String = "_Neo4j$name",
        val typeDefinition: TypeDefinition = TypeDefinition(name, prefixedName)
) : Neo4jSimpleConverter(name)

open class Neo4jSimpleConverter(val name: String) {
    protected fun toExpression(parameter: Expression): Expression {
        return Cypher.call(name.toLowerCase()).withArgs(parameter).asFunction()
    }

    open fun createCondition(
            property: Property,
            parameter: Parameter<*>,
            conditionCreator: (Expression, Expression) -> Condition
    ): Condition = conditionCreator(property, parameter)

    open fun createCondition(
            fieldName: String,
            field: GraphQLFieldDefinition,
            parameter: Parameter<*>,
            conditionCreator: (Expression, Expression) -> Condition,
            propertyContainer: PropertyContainer
    ): Condition = createCondition(propertyContainer.property(field.name, fieldName), parameter, conditionCreator)

    open fun projectField(variable: SymbolicName, field: SelectedField, name: String): Any = variable.property(field.name, name)

    open fun getMutationExpression(value: Any, field: GraphQLFieldDefinition): BaseDataFetcherForContainer.PropertyAccessor {
        return BaseDataFetcherForContainer.PropertyAccessor(field.name)
        { variable -> toExpression(queryParameter(value, variable, field.name)) }
    }
}

fun getNeo4jTypeConverter(field: GraphQLFieldDefinition): Neo4jSimpleConverter = getNeo4jTypeConverter(field.type.innerName())

private fun getNeo4jTypeConverter(name: String): Neo4jSimpleConverter =
        neo4jConverter[name] ?: neo4jScalarConverter[name] ?: throw RuntimeException("Type $name not found")

private val neo4jConverter = listOf(
        Neo4jTimeConverter("LocalTime"),
        Neo4jTimeConverter("Date"),
        Neo4jTimeConverter("DateTime"),
        Neo4jTimeConverter("Time"),
        Neo4jTimeConverter("LocalDateTime"),
        Neo4jPointConverter("Point"),
).associateBy { it.prefixedName }

private val neo4jScalarConverter = listOf(
        Neo4jTemporalConverter("LocalTime"),
        Neo4jTemporalConverter("Date"),
        Neo4jTemporalConverter("DateTime"),
        Neo4jTemporalConverter("Time"),
        Neo4jTemporalConverter("LocalDateTime")
).associateBy { it.name }

val NEO4j_TEMPORAL_TYPES = neo4jScalarConverter.keys

val neo4jTypeDefinitions = neo4jConverter.values.map { it.typeDefinition }
