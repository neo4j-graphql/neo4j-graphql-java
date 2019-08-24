package org.neo4j.graphql.handler.projection

import graphql.language.*
import graphql.schema.DataFetchingEnvironment
import org.neo4j.graphql.*

open class ProjectionBase(val metaProvider: MetaProvider) {
    companion object {
        const val NATIVE_ID = "_id"
        const val ORDER_BY = "orderBy"
        const val FIRST = "first"
        const val OFFSET = "offset"
        const val FILTER = "filter"

        const val TYPE_NAME = "__typename"
    }

    fun orderBy(variable: String, args: MutableList<Argument>): String {
        val arg = args.find { it.name == ORDER_BY }
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
            .map { "$variable.${it[0]} ${it[1].toUpperCase()}" }
            .joinToString(", ")
    }

    fun where(variable: String, fieldDefinition: FieldDefinition, type: NodeFacade, arguments: List<Argument>, field: Field): Cypher {

        val all = preparePredicateArguments(fieldDefinition, arguments)
            .filterNot { listOf(FIRST, OFFSET, ORDER_BY).contains(it.name) }
            .plus(predicateForNeo4jTypes(type, field))
        val (filterExpressions, filterParams) =
                filterExpressions(all.find { it.name == FILTER }?.value, type)
                    .map { it.toExpression(variable, metaProvider) }
                    .let { expressions ->
                        expressions.map { it.query } to expressions.fold(emptyMap<String, Any?>()) { res, exp -> res + exp.params }
                    }
        val noFilter = all.filter { it.name != FILTER }
        // todo turn it into a Predicate too
        val eqExpression = noFilter.map {
            if (type.getFieldDefinition(it.name)?.isNativeId() == true)
                "ID($variable) = \$${paramName(variable, it.propertyName, it.value)}"
            else
                "$variable.${it.toCypherString(variable, false)}"
        }
        val expression = (eqExpression + filterExpressions).joinNonEmpty(" AND ", " WHERE ")
        return Cypher(expression, (filterParams + noFilter.map { (k, _, v) -> paramName(variable, k, v) to v }.toMap()))
    }

    private fun predicateForNeo4jTypes(type: NodeFacade, field: Field): Collection<Translator.CypherArgument> =
            type.fieldDefinitions()
                .filter { it.isNeo4jType() }
                .map { neo4jType ->
                    neo4jType to field.selectionSet.selections
                        .filterIsInstance<Field>()
                        .filter { it.name == neo4jType.name || it.alias == neo4jType.name }
                }
                .groupBy({ it.first }, { it.second }) // create a map of <FieldWithNeo4jType, List<Field>> so we group the data by the type
                .mapValues { it.value.flatten() }
                .flatMap { entry ->
                    // for each FieldWithNeo4jType of type query we create the where condition
                    val typeName = entry.key.type.name()
                    val fields = entry.value
                    val neo4jType = metaProvider.getNodeType(typeName)
                            ?: throw IllegalArgumentException("type $typeName not defined")
                    fields.flatMap { f ->
                        argumentsToMap(f.arguments, neo4jType)
                            .values
                            .map { arg ->
                                val (nameSuffix, propertyNameSuffix, innerNeo4jConstruct) = Neo4jQueryConversion
                                    .forQuery(arg, f, neo4jType)
                                Translator.CypherArgument(nameSuffix, propertyNameSuffix, arg.value, innerNeo4jConstruct)
                            }
                    }
                }

    private fun argumentsToMap(arguments: List<Argument>, resultObjectType: NodeFacade? = null): Map<String, Translator.CypherArgument> {
        return arguments
            .map { argument ->
                val propertyName = (resultObjectType?.getFieldDefinition(argument.name)?.propertyDirectiveName()
                        ?: argument.name).quote()
                argument.name to Translator.CypherArgument(argument.name.quote(), propertyName, argument.value.toJavaValue())
            }
            .toMap()
    }

    private fun filterExpressions(value: Any?, type: NodeFacade): List<Predicate> {
        // todo variable/parameter
        return if (value is Map<*, *>) {
            CompoundPredicate(value.map { (k, v) -> toExpression(k.toString(), v, type, metaProvider) }, "AND").parts
        } else emptyList()
    }

    fun propertyArguments(queryField: Field) =
            queryField.arguments.filterNot { listOf(FIRST, OFFSET, ORDER_BY).contains(it.name) }

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
                result += Translator.CypherArgument(it.name,
                        (fieldDefinition?.propertyDirectiveName() ?: it.name).quote(),
                        it.value.toJavaValue())
            }
            it.name to result
        }.toMap()
        val defaults = field.inputValueDefinitions.filter { it.defaultValue?.toJavaValue() != null && !predicates.containsKey(it.name) }
            .map { Translator.CypherArgument(it.name, it.name, it.defaultValue?.toJavaValue()) }
        return predicates.values.flatten() + defaults
    }

    private fun prepareFieldArguments(field: FieldDefinition, arguments: List<Argument>): List<Translator.CypherArgument> {
        // if (arguments.isEmpty()) return emptyList()
        val predicates = arguments.map { it.name to Translator.CypherArgument(it.name, it.name, it.value.toJavaValue()) }.toMap()
        val defaults = field.inputValueDefinitions.filter { it.defaultValue != null && !predicates.containsKey(it.name) }
            .map { Translator.CypherArgument(it.name, it.name, it.defaultValue.toJavaValue()) }
        return predicates.values + defaults
    }

    fun projectFields(variable: String, field: Field, nodeType: NodeFacade, env: DataFetchingEnvironment, variableSuffix: String?): Cypher {
        val queries = projectSelectionSet(variable, field.selectionSet, nodeType, env, variableSuffix)
        val projection = queries
            .map { it.query }
            .joinToString(", ", "{ ", " }")
        val params = queries
            .map { it.params }
            .fold(emptyMap<String, Any?>()) { res, map -> res + map }
        return Cypher("$variable $projection", params)
    }

    private fun projectSelectionSet(variable: String, selectionSet: SelectionSet, nodeType: NodeFacade, env: DataFetchingEnvironment, variableSuffix: String?): List<Cypher> {
        // TODO just render fragments on valid types (Labels) by using cypher like this:
        // apoc.map.mergeList([
        //  a{.name},
        //  CASE WHEN a:Location THEN a { .foo } ELSE {} END
        //  ])
        var hasTypeName = false
        val projections = selectionSet.selections.flatMapTo(mutableListOf<Cypher>()) {
            when (it) {
                is Field -> {
                    hasTypeName = hasTypeName || (it.name == TYPE_NAME)
                    listOf(projectField(variable, it, nodeType, env, variableSuffix))
                }
                is InlineFragment -> projectInlineFragment(variable, it, env, variableSuffix)
                is FragmentSpread -> projectNamedFragments(variable, it, env, variableSuffix)
                else -> emptyList()
            }
        }
        if (nodeType is InterfaceDefinitionNodeFacade
            && !hasTypeName
            && (env.getLocalContext() as? QueryContext)?.queryTypeOfInterfaces == true
        ) {
            // for interfaces the typename is required to determine the correct implementation
            projections.add(projectField(variable, Field(TYPE_NAME), nodeType, env, variableSuffix))
        }
        return projections
    }

    private fun projectField(variable: String, field: Field, type: NodeFacade, env: DataFetchingEnvironment, variableSuffix: String?): Cypher {
        if (field.name == TYPE_NAME) {
            return if (type.isRelationType()) {
                Cypher("${field.aliasOrName()}: '${type.name()}'")
            } else {
                val paramName = paramName(variable, "validTypes", null)
                val validTypeLabels = metaProvider.getValidTypeLabels(type)
                Cypher("${field.aliasOrName()}: head( [ label IN labels($variable) WHERE label IN $$paramName ] )",
                        mapOf(paramName to validTypeLabels))
            }
        }
        val fieldDefinition = type.getFieldDefinition(field.name)
                ?: throw IllegalStateException("No field ${field.name} in ${type.name()}")
        val cypherDirective = fieldDefinition.cypherDirective()
        val isObjectField = metaProvider.getNodeType(fieldDefinition.type.name()) != null
        return cypherDirective?.let {
            val directive = cypherDirective(variable, fieldDefinition, field, it, listOf(Translator.CypherArgument("this", "this", variable)))
            if (isObjectField) {
                val patternComprehensions = projectListComprehension(variable, field, fieldDefinition, env, directive, variableSuffix)
                Cypher(field.aliasOrName() + ":" + patternComprehensions.query, patternComprehensions.params)
            } else
                Cypher(field.aliasOrName() + ":" + directive.query, directive.params)

        } ?: when {
            isObjectField -> {
                val patternComprehensions = if (fieldDefinition.type.isNeo4jType()) {
                    projectNeo4jObjectType(variable, field)
                } else {
                    projectRelationship(variable, field, fieldDefinition, type, env, variableSuffix)
                }
                Cypher(field.aliasOrName() + ":" + patternComprehensions.query, patternComprehensions.params)
            }
            fieldDefinition.isNativeId() -> Cypher("${field.aliasOrName()}:ID($variable)")
            else -> {
                val dynamicPrefix = fieldDefinition.dynamicPrefix(metaProvider)
                when {
                    dynamicPrefix != null -> Cypher("${field.aliasOrName()}:apoc.map.fromPairs([key IN keys($variable) WHERE key STARTS WITH \"$dynamicPrefix\"| [substring(key,${dynamicPrefix.length}), $variable[key]]])")
                    field.aliasOrName() == field.propertyName(fieldDefinition) -> Cypher("." + field.propertyName(fieldDefinition))
                    else -> Cypher(field.aliasOrName() + ":" + variable + "." + field.propertyName(fieldDefinition))
                }
            }
        }
    }

    private fun projectNeo4jObjectType(variable: String, field: Field): Cypher {
        val fieldProjection = field.selectionSet.selections
            .filterIsInstance<Field>()
            .map {
                val value = when (it.name) {
                    NEO4j_FORMATTED_PROPERTY_KEY -> "$variable.${field.name}"
                    else -> "$variable.${field.name}.${it.name}"
                }
                "${it.name}: $value"
            }
            .joinToString(", ")

        return Cypher(" { $fieldProjection }")
    }

    fun cypherDirective(variable: String, fieldDefinition: FieldDefinition, field: Field, cypherDirective: Cypher, additionalArgs: List<Translator.CypherArgument>): Cypher {
        val suffix = if (fieldDefinition.type.isList()) "Many" else "Single"
        val (query, args) = cypherDirectiveQuery(variable, fieldDefinition, field, cypherDirective, additionalArgs)
        return Cypher("apoc.cypher.runFirstColumn$suffix($query)", args)
    }

    fun cypherDirectiveQuery(variable: String, fieldDefinition: FieldDefinition, field: Field, cypherDirective: Cypher, additionalArgs: List<Translator.CypherArgument>): Cypher {
        val args = additionalArgs + prepareFieldArguments(fieldDefinition, field.arguments)
        val argParams = args.map { '$' + it.name + " AS " + it.name }.joinNonEmpty(", ")
        val query = (if (argParams.isEmpty()) "" else "WITH $argParams ") + cypherDirective.escapedQuery()
        val argString = (args.map { it.name + ':' + if (it.name == "this") it.value else ('$' + paramName(variable, it.name, it.value)) }).joinToString(", ", "{ ", " }")
        return Cypher("'$query', $argString", args.filter { it.name != "this" }.associate { paramName(variable, it.name, it.value) to it.value })
    }

    private fun projectNamedFragments(variable: String, fragmentSpread: FragmentSpread, env: DataFetchingEnvironment, variableSuffix: String?) =
            env.fragmentsByName.getValue(fragmentSpread.name).let {
                projectFragment(it.typeCondition.name, variable, env, variableSuffix, it.selectionSet)
            }

    private fun projectInlineFragment(variable: String, fragment: InlineFragment, env: DataFetchingEnvironment, variableSuffix: String?) =
            projectFragment(fragment.typeCondition.name, variable, env, variableSuffix, fragment.selectionSet)

    private fun projectFragment(fragmentTypeName: String?, variable: String, env: DataFetchingEnvironment, variableSuffix: String?, selectionSet: SelectionSet): List<Cypher> {
        val fragmentType = metaProvider.getNodeType(fragmentTypeName) ?: return emptyList()
        // these are the nested fields of the fragment
        // it could be that we have to adapt the variable name too, and perhaps add some kind of rename
        return projectSelectionSet(variable, selectionSet, fragmentType, env, variableSuffix)
    }


    private fun projectRelationship(variable: String, field: Field, fieldDefinition: FieldDefinition, parent: NodeFacade, env: DataFetchingEnvironment, variableSuffix: String?): Cypher {
        return when (parent.getDirective(DirectiveConstants.RELATION) != null) {
            true -> projectRelationshipParent(variable, field, fieldDefinition, env, variableSuffix)
            else -> projectRichAndRegularRelationship(variable, field, fieldDefinition, parent, env)
        }
    }

    private fun projectListComprehension(variable: String, field: Field, fieldDefinition: FieldDefinition, env: DataFetchingEnvironment, expression: Cypher, variableSuffix: String?): Cypher {
        val fieldObjectType = metaProvider.getNodeType(fieldDefinition.type.name()) ?: return Cypher.EMPTY
        val fieldType = fieldDefinition.type
        val childVariable = variable + field.name.capitalize()

        // val where = where(childVariable, fieldDefinition, fieldObjectType, propertyArguments(field))
        val fieldProjection = projectFields(childVariable, field, fieldObjectType, env, variableSuffix)

        val comprehension = "[$childVariable IN ${expression.query} | ${fieldProjection.query}]"
        val skipLimit = SkipLimit(childVariable, field.arguments)
        val slice = skipLimit.slice(fieldType.isList())
        return Cypher(comprehension + slice.query, (expression.params + fieldProjection.params + slice.params)) // + where.params

    }

    private fun relationshipInfoInCorrectDirection(fieldObjectType: NodeFacade, relInfo0: RelationshipInfo, parent: NodeFacade, relDirectiveField: RelationshipInfo?): RelationshipInfo {
        val startField = fieldObjectType.getFieldDefinition(relInfo0.startField)!!
        val endField = fieldObjectType.getFieldDefinition(relInfo0.endField)!!
        val startFieldTypeName = startField.type.inner().name()
        val inverse = startFieldTypeName != parent.name() || startField.type.name() == endField.type.name() && relDirectiveField?.out != relInfo0.out
        return if (inverse) relInfo0.copy(out = relInfo0.out?.not(), startField = relInfo0.endField, endField = relInfo0.startField) else relInfo0
    }

    private fun projectRelationshipParent(variable: String, field: Field, fieldDefinition: FieldDefinition, env: DataFetchingEnvironment, variableSuffix: String?): Cypher {
        val fieldObjectType = metaProvider.getNodeType(fieldDefinition.type.name()) ?: return Cypher.EMPTY
        return projectFields(variable + (variableSuffix?.capitalize()
                ?: ""), field, fieldObjectType, env, variableSuffix)
    }

    private fun projectRichAndRegularRelationship(variable: String, field: Field, fieldDefinition: FieldDefinition, parent: NodeFacade, env: DataFetchingEnvironment): Cypher {
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

        val where = where(childVariable, fieldDefinition, nodeType, propertyArguments(field), field)
        val fieldProjection = projectFields(childVariable, field, nodeType, env, variableSuffix)

        val comprehension = "[($variable)$inArrow-[$relPattern]-$outArrow($endNodePattern)${where.query} | ${fieldProjection.query}]"
        val skipLimit = SkipLimit(childVariable, field.arguments)
        val slice = skipLimit.slice(fieldType.isList())
        return Cypher(comprehension + slice.query, (where.params + fieldProjection.params + slice.params))
    }

    private fun relDetails(type: NodeFacade, relDirective: Directive) =
            relDetails(type) { name, defaultValue -> metaProvider.getDirectiveArgument(relDirective, name, defaultValue) }

    class SkipLimit(variable: String,
            arguments: List<Argument>,
            private val skip: Translator.CypherArgument? = convertArgument(variable, arguments, OFFSET),
            private val limit: Translator.CypherArgument? = convertArgument(variable, arguments, FIRST)) {

        fun format(): Cypher {
            return if (skip != null) {
                if (limit != null) Cypher(" SKIP $${skip.propertyName} LIMIT $${limit.propertyName}", mapOf(
                        skip.propertyName to skip.value,
                        limit.propertyName to limit.value)
                )
                else Cypher(" SKIP $${skip.propertyName}", mapOf(skip.propertyName to skip.value))
            } else {
                if (limit != null) Cypher(" LIMIT $${limit.propertyName}", mapOf(limit.propertyName to limit.value))
                else Cypher.EMPTY
            }
        }

        fun slice(list: Boolean = false): Cypher {
            if (!list) {
                return if (skip != null) {
                    Cypher("[$${skip.propertyName}]", mapOf(skip.propertyName to skip.value))
                } else {
                    Cypher("[0]")
                }
            }

            return when (limit) {
                null -> when {
                    skip != null -> Cypher("[$${skip.propertyName}..]", mapOf(skip.propertyName to skip.value))
                    else -> Cypher.EMPTY
                }
                else -> when {
                    skip != null -> Cypher("[$${skip.propertyName}.. $${skip.propertyName} + $${limit.propertyName}]", mapOf(
                            skip.propertyName to skip.value,
                            limit.propertyName to limit.value))
                    else -> Cypher("[0..$${limit.propertyName}]", mapOf(limit.propertyName to limit.value))
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
