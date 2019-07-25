package org.neo4j.graphql.handler

import graphql.language.Argument
import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.language.ObjectValue
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*

class QueryHandler(
        type: NodeFacade,
        fieldDefinition: FieldDefinition,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        projectionRepository: ProjectionRepository)
    : BaseDataFetcher(type, fieldDefinition, typeDefinitionRegistry, projectionRepository) {

    private val isList = fieldDefinition.type.isList()
    private val metaProvider = TypeRegistryMetaProvider(typeDefinitionRegistry)

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Translator.Cypher, ctx: Translator.Context): Translator.Cypher {

        val mapProjection = projectionProvider.invoke()
        val ordering = orderBy(variable, field.arguments)
        val skipLimit = ProjectionHandler.SkipLimit(variable, field.arguments).format()

        val select = if (type.isRelationType()) {
            "()-[$variable:${label()}]->()"
        } else {
            "($variable:${label()})"
        }
        val where = where(variable, propertyArguments(field), ctx)
        return Translator.Cypher("MATCH $select${where.query}" +
                " RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}",
                (where.params + mapProjection.params + skipLimit.params),
                isList)
    }

    private fun where(variable: String, arguments: List<Argument>, ctx: Translator.Context): Translator.Cypher {
        return where(variable, fieldDefinition, type, arguments, ctx)
    }

    private fun where(variable: String, field: FieldDefinition, type: NodeFacade, arguments: List<Argument>, ctx: Translator.Context): Translator.Cypher {
        val (objectFilterExpression, objectFilterParams) = ctx.objectFilterProvider?.invoke(variable, type)
            ?.let { listOf(it.query) to it.params } ?: (emptyList<String>() to emptyMap())

        val all = preparePredicateArguments(field, arguments).filterNot { listOf("first", "offset", "orderBy").contains(it.name) }
        val (filterExpressions, filterParams) =
                filterExpressions(all.find { it.name == "filter" }?.value, type)
                    .map { it.toExpression(variable, metaProvider) }
                    .let { expressions ->
                        expressions.map { it.query } to expressions.fold(emptyMap<String, Any?>()) { res, exp -> res + exp.params }
                    }
        val noFilter = all.filter { it.name != "filter" }
        // todo turn it into a Predicate too
        val eqExpression = noFilter.map { (k, p, v) ->
            (if (type.getFieldDefinition(k)?.isNativeId() == true) "ID($variable)" else "$variable.${p.quote()}") + " = \$${paramName(variable, k, v)}"
        }
        val expression = (objectFilterExpression + eqExpression + filterExpressions).joinNonEmpty(" AND ", " WHERE ")
        return Translator.Cypher(expression, objectFilterParams + (filterParams + noFilter.map { (k, _, v) -> paramName(variable, k, v) to v }.toMap()))
    }

    // TODO Duplicate
    private fun propertyArguments(queryField: Field) =
            // TODO constants
            queryField.arguments.filterNot { listOf("first", "offset", "orderBy").contains(it.name) }

    @Deprecated(message = "get other type")
    private fun preparePredicateArguments(field: FieldDefinition, arguments: List<Argument>): List<Translator.CypherArgument> {
        if (arguments.isEmpty()) return emptyList()
        val resultObjectType = metaProvider.getNodeType(field.type.name())
                ?: throw IllegalArgumentException("${field.name} cannot be converted to a NodeGraphQlFacade")
        val predicates = arguments.map {
            val fieldDefinition = resultObjectType.getFieldDefinition(it.name)
            val dynamicPrefix = fieldDefinition?.dynamicPrefix(metaProvider)
            val result = mutableListOf<Translator.CypherArgument>()
            if (dynamicPrefix != null && it.value is ObjectValue) {
                for (argField in (it.value as ObjectValue).objectFields) {
                    result += Translator.CypherArgument(it.name + argField.name.capitalize(), dynamicPrefix + argField.name, argField.value.toJavaValue())
                }

            } else {
                result += Translator.CypherArgument(it.name, fieldDefinition?.propertyDirectiveName()
                        ?: it.name, it.value.toJavaValue())
            }
            it.name to result
        }.toMap()
        val defaults = field.inputValueDefinitions.filter { it.defaultValue?.toJavaValue() != null && !predicates.containsKey(it.name) }
            .map { Translator.CypherArgument(it.name, it.name, it.defaultValue?.toJavaValue()) }
        return predicates.values.flatten() + defaults
    }

    private fun filterExpressions(value: Any?, type: NodeFacade): List<Predicate> {
        // todo variable/parameter
        return if (value is Map<*, *>) {
            CompoundPredicate(value.map { (k, v) -> toExpression(k.toString(), v, type, metaProvider) }, "AND").parts
        } else emptyList()
    }

}