package org.neo4j.graphql.handler

import graphql.language.Argument
import graphql.language.ArrayValue
import graphql.language.ObjectValue
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import org.neo4j.graphql.*

abstract class BaseDataFetcherForContainer(
        val type: GraphQLFieldsContainer,
        fieldDefinition: GraphQLFieldDefinition
) : BaseDataFetcher(fieldDefinition) {

    val propertyFields: MutableMap<String, (Any) -> List<Translator.CypherArgument>?> = mutableMapOf()
    val defaultFields: MutableMap<String, Any> = mutableMapOf()

    init {
        fieldDefinition
            .arguments
            .filterNot { listOf(FIRST, OFFSET, ORDER_BY, NATIVE_ID).contains(it.name) }
            .onEach { arg ->
                if (arg.defaultValue != null) {
                    defaultFields[arg.name] = arg.defaultValue
                }
            }
            .mapNotNull { type.getFieldDefinition(it.name) }
            .forEach { field ->
                val dynamicPrefix = field.dynamicPrefix()
                propertyFields[field.name] = when {
                    dynamicPrefix != null -> dynamicPrefixCallback(field, dynamicPrefix)
                    field.isNeo4jType() -> neo4jTypeCallback(field)
                    else -> defaultCallback(field)
                }
            }
    }

    private fun defaultCallback(field: GraphQLFieldDefinition) =
            { value: Any ->
                val propertyName = field.propertyName()
                listOf(Translator.CypherArgument(field.name, propertyName.quote(), value.toJavaValue()))
            }

    private fun neo4jTypeCallback(field: GraphQLFieldDefinition) =
            { value: Any ->
                val (name, propertyName, converter) = Neo4jQueryConversion
                    .forMutation(value, field)
                listOf(Translator.CypherArgument(name, propertyName, value.toJavaValue(), converter, propertyName))
            }

    private fun dynamicPrefixCallback(field: GraphQLFieldDefinition, dynamicPrefix: String) =
            { value: Any ->
                // maps each property of the map to the node
                (value as? ObjectValue)?.objectFields?.map { argField ->
                    Translator.CypherArgument(
                            (field.name + argField.name.capitalize()),
                            (dynamicPrefix + argField.name).quote(),
                            argField.value.toJavaValue()
                    )
                }
            }


    fun allLabels(): String = type.allLabels()

    fun label(includeAll: Boolean = false) = if (includeAll) type.allLabels() else type.quotedLabel()


    protected fun properties(variable: String, arguments: List<Argument>): Cypher {
        val all = preparePredicateArguments(arguments)
        if (all.isEmpty()) {
            return Cypher.EMPTY
        }
        val query = all
            .joinToString(", ", " { ", " }") { it.toCypherString(variable) }
        val params = all
            .map { paramName(variable, it.cypherParam, it.value) to it.value }
            .toMap()
        return Cypher(query, params)
    }

    private fun preparePredicateArguments(arguments: List<Argument>): List<Translator.CypherArgument> {
        val predicates = arguments
            .mapNotNull { argument ->
                propertyFields[argument.name]?.invoke(argument.value)?.let { argument.name to it }
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
                isRelation: Boolean,
                paramName: String? = idProperty?.let { paramName(variable, idProperty.name, idProperty.value) }
        ): Cypher {
            return when {
                idProperty != null && paramName != null -> {
                    val queryParams = mapOf(paramName to idProperty.value.toJavaValue())
                    if (idField.isNativeId()) {
                        if (isRelation) {
                            Cypher("()-[$variable:$label]->() WHERE ID($variable) = toInteger($$paramName)", queryParams)
                        } else {
                            Cypher("($variable:$label) WHERE ID($variable) = toInteger($$paramName)", queryParams)
                        }
                    } else {
                        // TODO handle @property aliasing
                        if (idProperty.value is ArrayValue) {
                            Cypher("($variable:$label) WHERE  $variable.${idField.name.quote()} IN $$paramName", queryParams)
                        } else {
                            Cypher("($variable:$label { ${idField.name.quote()}: $$paramName })", queryParams)
                        }
                    }
                }
                else -> Cypher.EMPTY
            }
        }
    }
}
