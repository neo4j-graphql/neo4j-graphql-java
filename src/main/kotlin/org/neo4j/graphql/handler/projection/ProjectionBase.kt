package org.neo4j.graphql.handler.projection

import graphql.language.*
import org.neo4j.graphql.*

open class ProjectionBase(val metaProvider: MetaProvider) {

    fun where(variable: String, field: FieldDefinition, type: NodeFacade, arguments: List<Argument>, ctx: Translator.Context): Translator.Cypher {
        val (objectFilterExpression, objectFilterParams) = ctx.objectFilterProvider?.invoke(variable, type)
            ?.let { listOf(it.query) to it.params } ?: (emptyList<String>() to emptyMap())

        // TODO constants
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

    fun propertyArguments(queryField: Field) =
            // TODO constants
            queryField.arguments.filterNot { listOf("first", "offset", "orderBy").contains(it.name) }

    fun cypherDirective(variable: String, fieldDefinition: FieldDefinition, field: Field, cypherDirective: Translator.Cypher, additionalArgs: List<Translator.CypherArgument>): Translator.Cypher {
        val suffix = if (fieldDefinition.type.isList()) "Many" else "Single"
        val (query, args) = cypherDirectiveQuery(variable, fieldDefinition, field, cypherDirective, additionalArgs)
        return Translator.Cypher("apoc.cypher.runFirstColumn$suffix($query)", args)
    }

    fun cypherDirectiveQuery(variable: String, fieldDefinition: FieldDefinition, field: Field, cypherDirective: Translator.Cypher, additionalArgs: List<Translator.CypherArgument>): Translator.Cypher {
        val args = additionalArgs + prepareFieldArguments(fieldDefinition, field.arguments)
        val argParams = args.map { '$' + it.name + " AS " + it.name }.joinNonEmpty(", ")
        val query = (if (argParams.isEmpty()) "" else "WITH $argParams ") + cypherDirective.escapedQuery()
        val argString = (args.map { it.name + ':' + if (it.name == "this") it.value else ('$' + paramName(variable, it.name, it.value)) }).joinToString(", ", "{ ", " }")
        return Translator.Cypher("'$query', $argString", args.filter { it.name != "this" }.associate { paramName(variable, it.name, it.value) to it.value })
    }

    private fun prepareFieldArguments(field: FieldDefinition, arguments: List<Argument>): List<Translator.CypherArgument> {
        // if (arguments.isEmpty()) return emptyList()
        val predicates = arguments.map { it.name to Translator.CypherArgument(it.name, it.name, it.value.toJavaValue()) }.toMap()
        val defaults = field.inputValueDefinitions.filter { it.defaultValue != null && !predicates.containsKey(it.name) }
            .map { Translator.CypherArgument(it.name, it.name, it.defaultValue.toJavaValue()) }
        return predicates.values + defaults
    }

    fun orderBy(variable: String, args: MutableList<Argument>): String {
        val arg = args.find { it.name == "orderBy" }
        val values = arg?.value?.let { it ->
            when (it) {
                is ArrayValue -> it.values.map { it.toJavaValue().toString() }
                is EnumValue -> listOf(it.name)
                is StringValue -> listOf(it.value)
                else -> null
            }
        }
        return if (values == null) ""
        else " ORDER BY " + values
            .map { it.split("_") }
            .joinToString(", ") { "$variable.${it[0]} ${it[1].toUpperCase()}" }
    }

    class SkipLimit(variable: String,
            arguments: List<Argument>,
            private val skip: Translator.CypherArgument? = convertArgument(variable, arguments, "offset"),
            private val limit: Translator.CypherArgument? = convertArgument(variable, arguments, "first")) {

        fun format(): Translator.Cypher {
            return if (skip != null) {
                if (limit != null) Translator.Cypher(" SKIP $${skip.propertyName} LIMIT $${limit.propertyName}", mapOf(
                        skip.propertyName to skip.value,
                        limit.propertyName to limit.value)
                )
                else Translator.Cypher(" SKIP $${skip.propertyName}", mapOf(skip.propertyName to skip.value))
            } else {
                if (limit != null) Translator.Cypher(" LIMIT $${limit.propertyName}", mapOf(limit.propertyName to limit.value))
                else Translator.Cypher.EMPTY
            }
        }

        fun slice(list: Boolean = false): Translator.Cypher {
            if (!list) {
                return if (skip != null) {
                    Translator.Cypher("[$${skip.propertyName}]", mapOf(skip.propertyName to skip.value))
                } else {
                    Translator.Cypher("[0]")
                }
            }

            return when (limit) {
                null -> when {
                    skip != null -> Translator.Cypher("[$${skip.propertyName}..]", mapOf(skip.propertyName to skip.value))
                    else -> Translator.Cypher.EMPTY
                }
                else -> when {
                    skip != null -> Translator.Cypher("[$${skip.propertyName}.. $${skip.propertyName} + $${limit.propertyName}]", mapOf(
                            skip.propertyName to skip.value,
                            limit.propertyName to limit.value))
                    else -> Translator.Cypher("[0..$${limit.propertyName}]", mapOf(limit.propertyName to limit.value))
                }
            }
        }

        companion object {
            private fun convertArgument(variable: String, arguments: List<Argument>, name: String): Translator.CypherArgument? {
                val argument = arguments.find { it.name.toLowerCase() == name } ?: return null
                return Translator.CypherArgument(name, paramName(variable, argument.name, argument), argument.value?.toJavaValue())
            }
        }
    }
}