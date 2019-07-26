package org.neo4j.graphql.handler.projection

import graphql.language.*
import org.neo4j.graphql.*

open class ProjectionBase(val metaProvider: MetaProvider) {

    fun projectFields(variable: String, field: Field, nodeType: NodeFacade, ctx: Translator.Context, variableSuffix: String?): Translator.Cypher {
        val properties = field.selectionSet.selections.flatMap {
            when (it) {
                is Field -> listOf(projectField(variable, it, nodeType, ctx, variableSuffix))
                is InlineFragment -> projectInlineFragment(variable, it, nodeType, ctx, variableSuffix)
                is FragmentSpread -> projectNamedFragments(variable, it, nodeType, ctx, variableSuffix)
                else -> emptyList()
            }
        }

        val projection = properties.joinToString(", ", "{ ", " }") { it.query }
        val params = properties.map { it.params }.fold(emptyMap<String, Any?>()) { res, map -> res + map }
        return Translator.Cypher("$variable $projection", params)
    }

    private fun projectField(variable: String, field: Field, type: NodeFacade, ctx: Translator.Context, variableSuffix: String?): Translator.Cypher {
        if (field.name == "__typename") {
            val paramName = paramName(variable, "validTypes", null)
            val validTypeLabels = metaProvider.getValidTypeLabels(type)
            return Translator.Cypher("${field.aliasOrName()}: head( [ label in labels($variable) WHERE label in $$paramName ] )",
                    mapOf(paramName to validTypeLabels))
        }
        val fieldDefinition = type.getFieldDefinition(field.name)
                ?: throw IllegalStateException("No field ${field.name} in ${type.name()}")
        val cypherDirective = fieldDefinition.cypherDirective()
        val isObjectField = metaProvider.getNodeType(fieldDefinition.type.name()) != null
        return cypherDirective?.let {
            val directive = cypherDirective(variable, fieldDefinition, field, it, listOf(Translator.CypherArgument("this", "this", variable)))
            if (isObjectField) {
                val patternComprehensions = projectListComprehension(variable, field, fieldDefinition, ctx, directive, variableSuffix)
                Translator.Cypher(field.aliasOrName() + ":" + patternComprehensions.query, patternComprehensions.params)
            } else
                Translator.Cypher(field.aliasOrName() + ":" + directive.query, directive.params)

        } ?: when {
            isObjectField -> {
                val patternComprehensions = projectRelationship(variable, field, fieldDefinition, type, ctx, variableSuffix)
                Translator.Cypher(field.aliasOrName() + ":" + patternComprehensions.query, patternComprehensions.params)
            }
            fieldDefinition.isNativeId() -> Translator.Cypher("${field.aliasOrName()}:ID($variable)")
            else -> {
                val dynamicPrefix = fieldDefinition.dynamicPrefix(metaProvider)
                when {
                    dynamicPrefix != null -> Translator.Cypher("${field.aliasOrName()}:apoc.map.fromPairs([key IN keys($variable) WHERE key STARTS WITH \"$dynamicPrefix\"| [substring(key,${dynamicPrefix.length}), $variable[key]]])")
                    field.aliasOrName() == field.propertyName(fieldDefinition) -> Translator.Cypher("." + field.propertyName(fieldDefinition))
                    else -> Translator.Cypher(field.aliasOrName() + ":" + variable + "." + field.propertyName(fieldDefinition))
                }
            }
        }
    }

    private fun projectNamedFragments(variable: String, fragmentSpread: FragmentSpread, type: NodeFacade, ctx: Translator.Context, variableSuffix: String?) =
            ctx.fragments.getValue(fragmentSpread.name).let {
                projectFragment(it.typeCondition.name, type, variable, ctx, variableSuffix, it.selectionSet)
            }

    private fun projectFragment(fragmentTypeName: String?, type: NodeFacade, variable: String, ctx: Translator.Context, variableSuffix: String?, selectionSet: SelectionSet): List<Translator.Cypher> {
        val fragmentType = metaProvider.getNodeType(fragmentTypeName)
        return if (fragmentType == type) {
            // these are the nested fields of the fragment
            // it could be that we have to adapt the variable name too, and perhaps add some kind of rename
            selectionSet.selections.filterIsInstance<Field>().map { projectField(variable, it, fragmentType, ctx, variableSuffix) }
        } else {
            emptyList()
        }
    }

    private fun projectInlineFragment(variable: String, fragment: InlineFragment, type: NodeFacade, ctx: Translator.Context, variableSuffix: String?) =
            projectFragment(fragment.typeCondition.name, type, variable, ctx, variableSuffix, fragment.selectionSet)


    private fun projectRelationship(variable: String, field: Field, fieldDefinition: FieldDefinition, parent: NodeFacade, ctx: Translator.Context, variableSuffix: String?): Translator.Cypher {
        return when (parent.getDirective(DirectiveConstants.RELATION) != null) {
            true -> projectRelationshipParent(variable, field, fieldDefinition, ctx, variableSuffix)
            else -> projectRichAndRegularRelationship(variable, field, fieldDefinition, parent, ctx)
        }
    }

    private fun projectListComprehension(variable: String, field: Field, fieldDefinition: FieldDefinition, ctx: Translator.Context, expression: Translator.Cypher, variableSuffix: String?): Translator.Cypher {
        val fieldObjectType = metaProvider.getNodeType(fieldDefinition.type.name()) ?: return Translator.Cypher.EMPTY
        val fieldType = fieldDefinition.type
        val childVariable = variable + field.name.capitalize()

        // val where = where(childVariable, fieldDefinition, fieldObjectType, propertyArguments(field))
        val fieldProjection = projectFields(childVariable, field, fieldObjectType, ctx, variableSuffix)

        val comprehension = "[$childVariable IN ${expression.query} | ${fieldProjection.query}]"
        val skipLimit = SkipLimit(childVariable, field.arguments)
        val slice = skipLimit.slice(fieldType.isList())
        return Translator.Cypher(comprehension + slice.query, (expression.params + fieldProjection.params + slice.params)) // + where.params

    }

    private fun relationshipInfoInCorrectDirection(fieldObjectType: NodeFacade, relInfo0: RelationshipInfo, parent: NodeFacade, relDirectiveField: RelationshipInfo?): RelationshipInfo {
        val startField = fieldObjectType.getFieldDefinition(relInfo0.startField)!!
        val endField = fieldObjectType.getFieldDefinition(relInfo0.endField)!!
        val startFieldTypeName = startField.type.inner().name()
        val inverse = startFieldTypeName != parent.name() || startField.type.name() == endField.type.name() && relDirectiveField?.out != relInfo0.out
        return if (inverse) relInfo0.copy(out = relInfo0.out?.not(), startField = relInfo0.endField, endField = relInfo0.startField) else relInfo0
    }

    private fun projectRelationshipParent(variable: String, field: Field, fieldDefinition: FieldDefinition, ctx: Translator.Context, variableSuffix: String?): Translator.Cypher {
        val fieldObjectType = metaProvider.getNodeType(fieldDefinition.type.name()) ?: return Translator.Cypher.EMPTY

        val fieldProjection = projectFields(variable + (variableSuffix?.capitalize()
                ?: ""), field, fieldObjectType, ctx, variableSuffix)

        return Translator.Cypher(fieldProjection.query)
    }

    private fun projectRichAndRegularRelationship(variable: String, field: Field, fieldDefinition: FieldDefinition, parent: NodeFacade, ctx: Translator.Context): Translator.Cypher {
        val fieldType = fieldDefinition.type
        val fieldTypeName = fieldDefinition.type.name()!!
        val nodeType = metaProvider.getNodeType(fieldTypeName)
                ?: throw IllegalArgumentException("$fieldTypeName cannot be converted to a NodeGraphQlFacade")

        // todo combine both nestings if rel-entity
        val relDirectiveObject = nodeType.getDirective(DirectiveConstants.RELATION)?.let { relDetails(nodeType, it) }
        val relDirectiveField = fieldDefinition.getDirective(DirectiveConstants.RELATION)?.let { relDetails(nodeType, it) }

        val (relInfo0, isRelFromType) =
                relDirectiveObject?.let { it to true }
                        ?: relDirectiveField?.let { it to false }
                        ?: throw IllegalStateException("Field $field needs an @relation directive")

        val relInfo = if (isRelFromType) relationshipInfoInCorrectDirection(nodeType, relInfo0, parent, relDirectiveField) else relInfo0

        val (inArrow, outArrow) = relInfo.arrows

        val childVariable = variable + field.name.capitalize()

        val (endNodePattern, variableSuffix) = when {
            isRelFromType -> {
                val label = nodeType.getFieldDefinition(relInfo.endField!!)!!.type.inner().name()
                ("$childVariable${relInfo.endField.capitalize()}:$label" to relInfo.endField)
            }
            else -> ("$childVariable:${nodeType.name()}" to null)
        }

        val relPattern = if (isRelFromType) "$childVariable:${relInfo.relType}" else ":${relInfo.relType}"

        // TODO abg cascade into type
        val where = where(childVariable, fieldDefinition, nodeType, propertyArguments(field), ctx)
        val fieldProjection = projectFields(childVariable, field, nodeType, ctx, variableSuffix)

        val comprehension = "[($variable)$inArrow-[$relPattern]-$outArrow($endNodePattern)${where.query} | ${fieldProjection.query}]"
        val skipLimit = SkipLimit(childVariable, field.arguments)
        val slice = skipLimit.slice(fieldType.isList())
        return Translator.Cypher(comprehension + slice.query, (where.params + fieldProjection.params + slice.params))
    }

    private fun relDetails(type: NodeFacade, relDirective: Directive) =
            relDetails(type) { name, defaultValue -> metaProvider.getDirectiveArgument(relDirective, name, defaultValue) }


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