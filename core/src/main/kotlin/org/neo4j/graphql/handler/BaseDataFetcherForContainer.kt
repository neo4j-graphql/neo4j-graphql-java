package org.neo4j.graphql.handler

import graphql.language.Argument
import graphql.language.ArrayValue
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLType
import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.Cypher.*
import org.neo4j.graphql.*

/**
 * This is a base class for all Node or Relation related data fetcher.
 */
abstract class BaseDataFetcherForContainer(schemaConfig: SchemaConfig) : BaseDataFetcher(schemaConfig) {

    lateinit var type: GraphQLFieldsContainer
    val propertyFields: MutableMap<String, (Any) -> List<PropertyAccessor>?> = mutableMapOf()
    val defaultFields: MutableMap<String, Any> = mutableMapOf()

    override fun initDataFetcher(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType) {
        type = fieldDefinition.type.inner() as? GraphQLFieldsContainer
                ?: throw IllegalStateException("expect type of field ${parentType.name()}.${fieldDefinition.name} to be GraphQLFieldsContainer, but was ${fieldDefinition.type.name()}")
        fieldDefinition
            .arguments
            .filterNot { listOf(FIRST, OFFSET, ORDER_BY, NATIVE_ID, OPTIONS).contains(it.name) }
            .onEach { arg ->
                if (arg.argumentDefaultValue.isSet) {
                    arg.argumentDefaultValue.value?.let { defaultFields[arg.name] = it }
                }
            }
            .mapNotNull { type.getRelevantFieldDefinition(it.name) }
            .forEach { field ->
                val dynamicPrefix = field.dynamicPrefix()
                propertyFields[field.name] = when {
                    dynamicPrefix != null -> dynamicPrefixCallback(field, dynamicPrefix)
                    field.isNeo4jType() || (schemaConfig.useTemporalScalars && field.isNeo4jTemporalType()) -> neo4jTypeCallback(field)
                    else -> defaultCallback(field)
                }
            }
    }

    private fun defaultCallback(field: GraphQLFieldDefinition) =
            { value: Any? ->
                val propertyName = field.propertyName()
                listOf(PropertyAccessor(propertyName) { variable -> queryParameter(value, variable, field.name) })
            }

    private fun neo4jTypeCallback(field: GraphQLFieldDefinition): (Any) -> List<PropertyAccessor> {
        val converter = getNeo4jTypeConverter(field)
        return { value: Any -> listOf(converter.getMutationExpression(value, field)) }
    }

    private fun dynamicPrefixCallback(field: GraphQLFieldDefinition, dynamicPrefix: String) =
            { value: Any ->
                // maps each property of the map to the node
                (value as? Map<*, *>)?.map { (key, value) ->
                    PropertyAccessor(
                            "$dynamicPrefix${key}"
                    ) { variable -> queryParameter(value, variable, "${field.name}${(key as String).capitalize()}") }
                }
            }


    protected fun properties(variable: String, arguments: Map<String, Any>): Array<Any> =
            preparePredicateArguments(arguments)
                .flatMap { listOf(it.propertyName, it.toExpression(variable)) }
                .toTypedArray()

    private fun preparePredicateArguments(arguments: Map<String, Any>): List<PropertyAccessor> {
        val predicates = arguments
            .entries
            .mapNotNull { (key, value) ->
                propertyFields[key]?.invoke(value)?.let { key to it }
            }
            .toMap()

        val defaults = defaultFields
            .filter { !predicates.containsKey(it.key) }
            .flatMap { (argName, defaultValue) -> propertyFields[argName]?.invoke(defaultValue) ?: emptyList() }
        return predicates.values.flatten() + defaults
    }

    companion object {
        fun getSelectQuery(
                variable: String,
                label: String?,
                idProperty: Argument?,
                idField: GraphQLFieldDefinition,
                isRelation: Boolean
        ): Pair<PropertyContainer, Condition> {
            return when {
                idProperty != null -> {
                    val idParam = queryParameter(idProperty.value, variable, idProperty.name)
                    if (idField.isNativeId()) {
                        val (propContainer, func) = if (isRelation) {
                            val variableName = name(variable)
                            val rel = anyNode().relationshipTo(anyNode(), label).named(variableName)
                            rel to Functions.id(rel)
                        } else {
                            val node = node(label).named(variable)
                            node to Functions.id(node)
                        }
                        val where = func.isEqualTo(call("toInteger").withArgs(idParam).asFunction())
                        propContainer to where
                    } else {
                        val node = node(label).named(variable)
                        // TODO handle @property aliasing
                        if (idProperty.value is ArrayValue) {
                            node to node.property(idField.name).`in`(idParam)
                        } else {
                            node.withProperties(idField.name, idParam) to Conditions.noCondition()
                        }
                    }
                }
                else -> throw IllegalArgumentException("Could not generate selection for ${if (isRelation) "Relation" else "Node"} $label b/c of missing ID field")
            }
        }
    }

    /**
     * @param propertyName the name used in neo4j
     * @param accessorFactory a factory for crating an expression to access the property
     */
    class PropertyAccessor(
            val propertyName: String,
            private val accessorFactory: (variable: String) -> Expression
    ) {

        fun toExpression(variable: String): Expression {
            return accessorFactory(variable)
        }
    }
}
