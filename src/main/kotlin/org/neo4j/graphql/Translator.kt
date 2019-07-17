package org.neo4j.graphql

import graphql.Scalars
import graphql.language.*
import graphql.language.TypeDefinition
import graphql.parser.Parser
import graphql.scalars.`object`.ObjectScalar
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION_OUT
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_NAME
import java.math.BigDecimal
import java.math.BigInteger

class Translator(val schema: GraphQLSchema) {
    data class Context @JvmOverloads constructor(val topLevelWhere: Boolean = true,
            val fragments: Map<String, FragmentDefinition> = emptyMap(),
            val temporal: Boolean = false,
            val query: CRUDConfig = CRUDConfig(),
            val mutation: CRUDConfig = CRUDConfig())

    data class CRUDConfig(val enabled: Boolean = true, val exclude: List<String> = emptyList())
    data class Cypher(val query: String, val params: Map<String, Any?> = emptyMap(), var list: Boolean = true) {
        fun with(p: Map<String, Any?>) = this.copy(params = this.params + p)
        fun escapedQuery() = query.replace("\"", "\\\"").replace("'", "\\'")

        companion object {
            val EMPTY = Cypher("")

            private fun findRelNodeId(objectType: GraphQLObjectType) = objectType.fieldDefinitions.find { it.isID() }!!

            private fun createRelStatement(source: GraphQLType, target: GraphQLFieldDefinition,
                    keyword: String = "MERGE"): String {
                val innerTarget = target.type.inner()
                val relationshipDirective = target.getDirective(RELATION)
                        ?: throw IllegalArgumentException("Missing @relation directive for relation ${target.name}")
                val targetFilterType = if (target.type.isList()) "IN" else "="
                val sourceId = findRelNodeId(source as GraphQLObjectType)
                val targetId = findRelNodeId(innerTarget as GraphQLObjectType)
                val (left, right) = if (relationshipDirective.getRelationshipDirection() == RELATION_DIRECTION_OUT) ("" to ">") else ("<" to "")
                return "MATCH (from:${source.label()} {${sourceId.name.quote()}:$${sourceId.name}}) " +
                        "MATCH (to:${innerTarget.label()}) WHERE to.${targetId.name.quote()} $targetFilterType $${target.name} " +
                        "$keyword (from)$left-[r:${relationshipDirective.getRelationshipType().quote()}]-$right(to) "
            }

            fun createRelationship(source: GraphQLType, target: GraphQLFieldDefinition): Cypher {
                return Cypher(createRelStatement(source, target))
            }

            fun deleteRelationship(source: GraphQLType, target: GraphQLFieldDefinition): Cypher {
                return Cypher(createRelStatement(source, target, "MATCH") +
                        "DELETE r ")
            }
        }
    }

    @JvmOverloads
    fun translate(query: String, params: Map<String, Any?> = emptyMap(), context: Context = Context()): List<Cypher> {
        val ast = parse(query) // todo preparsedDocumentProvider
        val ctx = context.copy(fragments = ast.definitions.filterIsInstance<FragmentDefinition>().map { it.name to it }.toMap())
        return ast.definitions.filterIsInstance<OperationDefinition>()
            .filter { it.operation == OperationDefinition.Operation.QUERY || it.operation == OperationDefinition.Operation.MUTATION } // todo variableDefinitions, directives, name
            .flatMap { it.selectionSet.selections }
            .filterIsInstance<Field>() // FragmentSpread, InlineFragment
            .map { it ->
                val cypher = toQuery(it, ctx)
                val resolvedParams = cypher.params.mapValues { toBoltValue(it.value, params) }
                cypher.with(resolvedParams) // was cypher.with(params)
            }
    }

    private fun toBoltValue(value: Any?, params: Map<String, Any?>) = when (value) {
        is VariableReference -> params[value.name]
        is BigInteger -> value.longValueExact()
        is BigDecimal -> value.toDouble()
        else -> value
    }

    private fun toQuery(field: Field, ctx: Context = Context()): Cypher {
        val name = field.name
        val queryType = schema.queryType.getFieldDefinition(name)
        val mutationType = schema.mutationType.getFieldDefinition(name)
        val fieldDefinition = queryType ?: mutationType
        ?: throw IllegalArgumentException("Unknown Query $name available queries: " + (schema.queryType.fieldDefinitions + schema.mutationType.fieldDefinitions).joinToString { it.name })
        val isQuery = queryType != null
        val isList = fieldDefinition.type.isList()
        val returnType = fieldDefinition.type.inner()
//        println(returnType)
        val type = schema.getType(returnType.name)
        val variable = field.aliasOrName().decapitalize()
        val cypherDirective = fieldDefinition.cypherDirective()
        val mapProjection = projectFields(variable, field, type, ctx, null)
        val skipLimit = SkipLimit(variable, field.arguments).format()
        val ordering = orderBy(variable, field.arguments)
        val relation = (type as? GraphQLObjectType)?.relationship(schema)
        if (cypherDirective != null) {
            // todo filters and such from nested fields
            return cypherQueryOrMutation(variable, fieldDefinition, field, cypherDirective, mapProjection, ordering, skipLimit, isQuery)
                .copy(list = isList)

        } else {
            if (isQuery) {
                val where = if (ctx.topLevelWhere) where(variable, fieldDefinition, type.getNodeType()!!, propertyArguments(field)) else Cypher.EMPTY
                val properties = if (ctx.topLevelWhere) Cypher.EMPTY else properties(variable, fieldDefinition, propertyArguments(field))
                return if (type.isRelationshipType()) {
                    Cypher("MATCH ()-[$variable:${type.label()}${properties.query}]->()${where.query} RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}",
                            (mapProjection.params + properties.params + where.params + skipLimit.params),
                            isList)
                } else {
                    Cypher("MATCH ($variable:${type.label()}${properties.query})${where.query} RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}",
                            (mapProjection.params + properties.params + where.params + skipLimit.params),
                            isList)
                }
            } else {
                // TODO add into Cypher companion object as did for the relationships
                val properties = properties(variable, fieldDefinition, propertyArguments(field))
                val returnStatement = "WITH $variable RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}"

                // Create
                if (type is GraphQLObjectType && name == "create" + type.name) {
                    if (type.isRelationship() && relation != null) {
                        val arguments = field.arguments.map { it.name to it }.toMap()
                        val (startSelect, startArgument) = getRelationSelect(true, relation, fieldDefinition, arguments)
                        val (endSelect, endArgument) = getRelationSelect(false, relation, fieldDefinition, arguments)

                        val createProperties = properties(variable, fieldDefinition, propertyArguments(field)
                            .filter { startArgument != it.name && endArgument != it.name })

                        return Cypher("MATCH ${startSelect.query}  MATCH ${endSelect.query} " +
                                "CREATE (${relation.startField})-[$variable:${relation.relType.quote()} ${createProperties.query}]->(${relation.endField})" +
                                " $returnStatement",
                                startSelect.params + endSelect.params + createProperties.params + skipLimit.params,
                                isList)
                    } else {
                        return Cypher("CREATE ($variable:${type.label(true)}${properties.query}) " + returnStatement,
                                (mapProjection.params + properties.params + skipLimit.params),
                                isList)
                    }
                }

                val idProperty = fieldDefinition.arguments.find { it.type.inner() == Scalars.GraphQLID }
                val isNativeId = idProperty != null && (type as? GraphQLObjectType)?.getFieldDefinition(idProperty.name)?.isNativeId() == true
                val select = if (relation != null) {
                    "MATCH ()-[$variable:${type.label()}]->() WHERE ID($variable) = $${paramName(variable, idProperty!!.name, properties.params[idProperty.name])}"
                } else {
                    when {
                        name.startsWith("merge") -> "MERGE "
                        else -> "MATCH "
                    } + getSelectQuery(variable, type.label(), idProperty, isNativeId, properties.params)
                }

                // Delete
                if (name == "delete" + type.name) {
                    val paramName = paramName(variable, idProperty!!.name, properties.params[idProperty.name]) // todo currently wrong, needs to be paramName
                    return Cypher("MATCH " + select +
                            "WITH $variable as toDelete, ${mapProjection.query} AS $variable $ordering${skipLimit.query} DETACH DELETE toDelete RETURN $variable",
                            (mapProjection.params + mapOf(paramName to properties.params[paramName]) + skipLimit.params),
                            isList)
                }

                // Merge or Update
                if (name == "merge" + type.name || name == "update" + type.name) {
                    val replace = !name.startsWith("merge")
                    val setProperties = setProperties(variable, fieldDefinition, propertyArguments(field), if (isNativeId) listOf(idProperty!!.name) else emptyList(), replace)
                    return Cypher(select + setProperties.query + returnStatement,
                            (mapProjection.params + properties.params),
                            isList)
                }

                // Relationships
                return checkRelationships(fieldDefinition, field, ordering, skipLimit, ctx)
                    .copy(list = isList)
            }
        }
    }

    private fun getRelationSelect(
            start: Boolean,
            relation: RelationshipInfo,
            fieldDefinition: GraphQLFieldDefinition,
            arguments: Map<String, Argument>): Pair<Cypher, String> {
        val typeResolver: (String?) -> NodeGraphQlFacade? = { s ->
            when (val type = schema.getType(s)) {
                is GraphQLObjectType -> ObjectNodeFacade(type)
                is GraphQLInterfaceType -> InterfaceNodeFacade(type)
                else -> null
            }
        }
        val relFieldName = if (start) relation.startField else relation.endField
        val idFields = relation.getRelatedIdFields(relFieldName, typeResolver)
        val field = idFields.firstOrNull { arguments.containsKey(it.argumentName) }
                ?: throw java.lang.IllegalArgumentException("No ID for the ${if (start) "start" else "end"} Type provided, one of ${idFields.map { it.argumentName }} is required")
        val params = mapOf((relFieldName + field.argumentName.capitalize()).quote() to arguments[field.argumentName]?.value?.toJavaValue())
        val cypher = getSelectQuery(relFieldName!!, field.declaringType.name(), fieldDefinition.getArgument(field.argumentName),
                field.field.isNativeId(),
                params
        )
        return Cypher(cypher, params) to field.argumentName
    }

    private fun getSelectQuery(variable: String, label: String?, idProperty: GraphQLArgument?, isNativeId: Boolean, params: Map<String, Any?>): String {
        return when {
            idProperty != null -> if (isNativeId) {
                "($variable:$label) WHERE ID($variable) = $${paramName(variable, idProperty.name, params[idProperty.name])} "
            } else {
                "($variable:$label { ${idProperty.name.quote()}: $${paramName(variable, idProperty.name, params[idProperty.name])} }) "
            }
            else -> ""
        }
    }

    private fun checkRelationships(sourceFieldDefinition: GraphQLFieldDefinition, field: Field, ordering: String, skipLimit: Cypher, ctx: Context): Cypher {
        val source = sourceFieldDefinition.type as GraphQLObjectType
        val targetFieldDefinition = filterTarget(source, field, sourceFieldDefinition)

        val sourceVariable = "from"
        val mapProjection = projectFields(sourceVariable, field, source, ctx, null)
        val returnStatement = "WITH DISTINCT $sourceVariable RETURN ${mapProjection.query} AS ${source.name.decapitalize().quote()}$ordering${skipLimit.query}"
        val properties = properties("", sourceFieldDefinition, propertyArguments(field)).params
            .mapKeys { it.key.decapitalize() }

        val targetFieldName = targetFieldDefinition.name
        val addMutationName = "add${source.name}${targetFieldName.capitalize()}"
        val deleteMutationName = "delete${source.name}${targetFieldName.capitalize()}"
        return when (field.name) {
            addMutationName -> {
                Cypher.createRelationship(source, targetFieldDefinition)
            }
            deleteMutationName -> {
                Cypher.deleteRelationship(source, targetFieldDefinition)
            }
            else -> throw IllegalArgumentException("Unknown Mutation ${sourceFieldDefinition.name}")
        }.let {
            it.copy(query = it.query + returnStatement, params = properties + skipLimit.params)
        }
    }

    private fun filterTarget(source: GraphQLObjectType, field: Field, graphQLFieldDefinition: GraphQLFieldDefinition): GraphQLFieldDefinition {
        return source.fieldDefinitions
            .filter {
                it.name.isNotBlank() && (field.name == "add${source.name}${it.name.capitalize()}"
                        || field.name == "delete${source.name}${it.name.capitalize()}")
            }
            .map { it }
            .firstOrNull() ?: throw IllegalArgumentException("Unknown Mutation ${graphQLFieldDefinition.name}")
    }

    private fun cypherQueryOrMutation(variable: String, fieldDefinition: GraphQLFieldDefinition, field: Field, cypherDirective: Cypher, mapProjection: Cypher, ordering: String, skipLimit: Cypher, isQuery: Boolean) =
            if (isQuery) {
                val (query, params) = cypherDirective(variable, fieldDefinition, field, cypherDirective, emptyList())
                Cypher("UNWIND $query AS $variable RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}",
                        (params + mapProjection.params + skipLimit.params))
            } else {
                val (query, params) = cypherDirectiveQuery(variable, fieldDefinition, field, cypherDirective, emptyList())
                Cypher("CALL apoc.cypher.doIt($query) YIELD value WITH value[head(keys(value))] AS $variable RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}",
                        (params + mapProjection.params + skipLimit.params))
            }


    private fun propertyArguments(queryField: Field) =
            queryField.arguments.filterNot { listOf("first", "offset", "orderBy").contains(it.name) }

    private fun orderBy(variable: String, args: MutableList<Argument>): String {
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
        else " ORDER BY " + values.map { it.split("_") }.joinToString(", ") { "$variable.${it[0]} ${it[1].toUpperCase()}" }
    }

    private fun where(variable: String, field: GraphQLFieldDefinition, type: NodeGraphQlFacade, arguments: List<Argument>): Cypher {
        val all = preparePredicateArguments(field, arguments).filterNot { listOf("first", "offset", "orderBy").contains(it.name) }
        if (all.isEmpty()) return Cypher("")
        val (filterExpressions, filterParams) =
                filterExpressions(all.find { it.name == "filter" }?.value, type)
                    .map { it.toExpression(variable, schema) }
                    .let { expressions ->
                        expressions.map { it.first } to expressions.fold(emptyMap<String, Any?>()) { res, exp -> res + exp.second }
                    }
        val noFilter = all.filter { it.name != "filter" }
        // todo turn it into a Predicate too
        val eqExpression = noFilter.map { (k, p, v) ->
            (if (type.getFieldDefinition(k)?.isNativeId() == true) "ID($variable)" else "$variable.${p.quote()}") + " = \$${paramName(variable, k, v)}"
        }
        val expression = (eqExpression + filterExpressions).joinNonEmpty(" AND ") // TODO talk to Will ,"(",")")
        return Cypher(" WHERE $expression", filterParams + noFilter.map { (k, _, v) -> paramName(variable, k, v) to v }.toMap()
        )
    }

    private fun filterExpressions(value: Any?, type: NodeGraphQlFacade): List<Predicate> {
        // todo variable/parameter
        return if (value is Map<*, *>) {
            CompoundPredicate(value.map { (k, v) -> toExpression(k.toString(), v, type) }, "AND").parts
        } else emptyList()
    }

    private fun properties(variable: String, field: GraphQLFieldDefinition, arguments: List<Argument>): Cypher {
        val all = preparePredicateArguments(field, arguments)
        return Cypher(all.joinToString(" , ", " {", "}") { (k, p, v) -> "${p.quote()}:\$${paramName(variable, k, v)}" },
                all.map { (k, _, v) -> paramName(variable, k, v) to v }.toMap())
    }

    private fun setProperties(variable: String, field: GraphQLFieldDefinition, arguments: List<Argument>,
            excludeProperties: List<String> = emptyList(), replace: Boolean): Cypher {
        val all = if (excludeProperties.isNotEmpty()) {
            preparePredicateArguments(field, arguments).filter { !excludeProperties.contains(it.name) }
        } else {
            preparePredicateArguments(field, arguments)
        }
        val merge = if (replace) "" else "+"
        return Cypher(all.joinToString(", ", " SET ${variable.quote()} $merge= { ", " } ") { (k, p, v) -> "${p.quote()}: \$${paramName(variable, k, v)}" },
                all.map { (k, _, v) -> paramName(variable, k, v) to v }.toMap())
    }

    data class CypherArgument(val name: String, val propertyName: String, val value: Any?)

    private fun preparePredicateArguments(field: GraphQLFieldDefinition, arguments: List<Argument>): List<CypherArgument> {
        if (arguments.isEmpty()) return emptyList()
        val resultObjectType = schema.getType(field.type.inner().name).getNodeType()
                ?: throw IllegalArgumentException("${field.type.inner().name} cannot be converted to a NodeGraphQlFacade")
        val predicates = arguments.map {
            val fieldDefinition = resultObjectType.getFieldDefinition(it.name)
            val dynamicPrefix = fieldDefinition?.dynamicPrefix(schema)
            val result = mutableListOf<CypherArgument>()
            if (dynamicPrefix != null && it.value is ObjectValue) {
                for (argField in (it.value as ObjectValue).objectFields) {
                    result += CypherArgument(it.name + argField.name.capitalize(), dynamicPrefix + argField.name, argField.value.toJavaValue())
                }

            } else {
                result += CypherArgument(it.name, fieldDefinition?.propertyDirectiveName()
                        ?: it.name, it.value.toJavaValue())
            }
            it.name to result
        }.toMap()
        val defaults = field.arguments.filter { it.defaultValue != null && !predicates.containsKey(it.name) }
            .map { CypherArgument(it.name, it.name, it.defaultValue) }
        return predicates.values.flatten() + defaults
    }

    private fun prepareFieldArguments(field: GraphQLFieldDefinition, arguments: List<Argument>): List<CypherArgument> {
        // if (arguments.isEmpty()) return emptyList()
        val predicates = arguments.map { it.name to CypherArgument(it.name, it.name, it.value.toJavaValue()) }.toMap()
        val defaults = field.arguments.filter { it.defaultValue != null && !predicates.containsKey(it.name) }
            .map { CypherArgument(it.name, it.name, it.defaultValue) }
        return predicates.values + defaults
    }

    private fun projectFields(variable: String, field: Field, type: GraphQLType, ctx: Context, variableSuffix: String?): Cypher {
        // todo handle non-object case
        val nodeType = type.getNodeType() ?: return Cypher("");

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
        return Cypher("$variable $projection", params)
    }

    private fun projectField(variable: String, field: Field, type: NodeGraphQlFacade, ctx: Context, variableSuffix: String?): Cypher {
        if (field.name == "__typename") {
            val paramName = paramName(variable, "validTypes", null);
            val validTypeLabels = type.getValidTypeLabels(schema)
            return Cypher("${field.aliasOrName()}: head( [ label in labels($variable) WHERE label in $$paramName ] )",
                    mapOf(paramName to validTypeLabels))
        }
        val fieldDefinition = type.getFieldDefinition(field.name)
                ?: throw IllegalStateException("No field ${field.name} in ${type.name()}")
        val cypherDirective = fieldDefinition.cypherDirective()
        val isObjectField = fieldDefinition.type.inner() is GraphQLObjectType || fieldDefinition.type.inner() is GraphQLInterfaceType
        return cypherDirective?.let {
            val directive = cypherDirective(variable, fieldDefinition, field, it, listOf(CypherArgument("this", "this", variable)))
            if (isObjectField) {
                val patternComprehensions = projectListComprehension(variable, field, fieldDefinition, ctx, directive, variableSuffix)
                Cypher(field.aliasOrName() + ":" + patternComprehensions.query, patternComprehensions.params)
            } else
                Cypher(field.aliasOrName() + ":" + directive.query, directive.params)

        } ?: when {
            isObjectField -> {
                val patternComprehensions = projectRelationship(variable, field, fieldDefinition, type, ctx, variableSuffix)
                Cypher(field.aliasOrName() + ":" + patternComprehensions.query, patternComprehensions.params)
            }
            fieldDefinition.isNativeId() -> Cypher("${field.aliasOrName()}:ID($variable)")
            else -> {
                val dynamicPrefix = fieldDefinition.dynamicPrefix(schema)
                when {
                    dynamicPrefix != null -> Cypher("${field.aliasOrName()}:apoc.map.fromPairs([key IN keys($variable) WHERE key STARTS WITH \"$dynamicPrefix\"| [substring(key,${dynamicPrefix.length}), $variable[key]]])")
                    field.aliasOrName() == field.propertyName(fieldDefinition) -> Cypher("." + field.propertyName(fieldDefinition))
                    else -> Cypher(field.aliasOrName() + ":" + variable + "." + field.propertyName(fieldDefinition))
                }
            }
        }
    }

    private fun cypherDirective(variable: String, fieldDefinition: GraphQLFieldDefinition, field: Field, cypherDirective: Cypher, additionalArgs: List<CypherArgument>): Cypher {
        val suffix = if (fieldDefinition.type.isList()) "Many" else "Single"
        val (query, args) = cypherDirectiveQuery(variable, fieldDefinition, field, cypherDirective, additionalArgs)
        return Cypher("apoc.cypher.runFirstColumn$suffix($query)", args)
    }

    private fun cypherDirectiveQuery(variable: String, fieldDefinition: GraphQLFieldDefinition, field: Field, cypherDirective: Cypher, additionalArgs: List<CypherArgument>): Cypher {
        val args = additionalArgs + prepareFieldArguments(fieldDefinition, field.arguments)
        val argParams = args.map { '$' + it.name + " AS " + it.name }.joinNonEmpty(", ")
        val query = (if (argParams.isEmpty()) "" else "WITH $argParams ") + cypherDirective.escapedQuery()
        val argString = (args.map { it.name + ':' + if (it.name == "this") it.value else ('$' + paramName(variable, it.name, it.value)) }).joinToString(", ", "{ ", " }")
        return Cypher("'$query', $argString", args.filter { it.name != "this" }.associate { paramName(variable, it.name, it.value) to it.value })
    }

    private fun projectNamedFragments(variable: String, fragmentSpread: FragmentSpread, type: NodeGraphQlFacade, ctx: Context, variableSuffix: String?) =
            ctx.fragments.getValue(fragmentSpread.name).let {
                projectFragment(it.typeCondition.name, type, variable, ctx, variableSuffix, it.selectionSet)
            }

    private fun projectFragment(fragmentTypeName: String?, type: NodeGraphQlFacade, variable: String, ctx: Context, variableSuffix: String?, selectionSet: SelectionSet): List<Cypher> {
        val fragmentType = schema.getNodeType(fragmentTypeName)
        return if (fragmentType == type) {
            // these are the nested fields of the fragment
            // it could be that we have to adapt the variable name too, and perhaps add some kind of rename
            selectionSet.selections.filterIsInstance<Field>().map { projectField(variable, it, fragmentType, ctx, variableSuffix) }
        } else {
            emptyList()
        }
    }

    private fun projectInlineFragment(variable: String, fragment: InlineFragment, type: NodeGraphQlFacade, ctx: Context, variableSuffix: String?) =
            projectFragment(fragment.typeCondition.name, type, variable, ctx, variableSuffix, fragment.selectionSet)


    private fun projectRelationship(variable: String, field: Field, fieldDefinition: GraphQLFieldDefinition, parent: NodeGraphQlFacade, ctx: Context, variableSuffix: String?): Cypher {
        return when (parent.getDirective(RELATION) != null) {
            true -> projectRelationshipParent(variable, field, fieldDefinition, ctx, variableSuffix)
            else -> projectRichAndRegularRelationship(variable, field, fieldDefinition, parent, ctx)
        }
    }

    private fun projectListComprehension(variable: String, field: Field, fieldDefinition: GraphQLFieldDefinition, ctx: Context, expression: Cypher, variableSuffix: String?): Cypher {
        val fieldType = fieldDefinition.type
        val fieldObjectType = fieldType.inner() as GraphQLObjectType
        val childVariable = variable + field.name.capitalize()

        // val where = where(childVariable, fieldDefinition, fieldObjectType, propertyArguments(field))
        val fieldProjection = projectFields(childVariable, field, fieldObjectType, ctx, variableSuffix)

        val comprehension = "[$childVariable IN ${expression.query} | ${fieldProjection.query}]"
        val skipLimit = SkipLimit(childVariable, field.arguments)
        val slice = skipLimit.slice(fieldType.isList())
        return Cypher(comprehension + slice.query, (expression.params + fieldProjection.params + slice.params)) // + where.params

    }

    private fun projectRichAndRegularRelationship(variable: String, field: Field, fieldDefinition: GraphQLFieldDefinition, parent: NodeGraphQlFacade, ctx: Context): Cypher {
        val fieldType = fieldDefinition.type
        val graphQLType = fieldType.inner()
        val nodeType = graphQLType.getNodeType()
                ?: throw IllegalArgumentException("$graphQLType cannot be converted to a NodeGraphQlFacade")

        // todo combine both nestings if rel-entity
        val relDirectiveObject = nodeType.getDirective(RELATION)?.let { relDetails(nodeType, it) }
        val relDirectiveField = fieldDefinition.definition.getDirective(RELATION)?.let { relDetails(nodeType, it) }

        val (relInfo0, isRelFromType) =
                relDirectiveObject?.let { it to true }
                        ?: relDirectiveField?.let { it to false }
                        ?: throw IllegalStateException("Field $field needs an @relation directive")

        val relInfo = if (isRelFromType) relationshipInfoInCorrectDirection(nodeType, relInfo0, parent, relDirectiveField) else relInfo0

        val (inArrow, outArrow) = relInfo.arrows

        val childVariable = variable + field.name.capitalize()

        val (endNodePattern, variableSuffix) = when {
            isRelFromType -> {
                val label = nodeType.getFieldDefinition(relInfo.endField!!)!!.type.inner().name
                ("$childVariable${relInfo.endField.capitalize()}:$label" to relInfo.endField)
            }
            else -> ("$childVariable:${nodeType.name()}" to null)
        }

        val relPattern = if (isRelFromType) "$childVariable:${relInfo.relType}" else ":${relInfo.relType}"

        val where = where(childVariable, fieldDefinition, nodeType, propertyArguments(field))
        val fieldProjection = projectFields(childVariable, field, graphQLType, ctx, variableSuffix)

        val comprehension = "[($variable)$inArrow-[$relPattern]-$outArrow($endNodePattern)${where.query} | ${fieldProjection.query}]"
        val skipLimit = SkipLimit(childVariable, field.arguments)
        val slice = skipLimit.slice(fieldType.isList())
        return Cypher(comprehension + slice.query, (where.params + fieldProjection.params + slice.params))
    }

    private fun relationshipInfoInCorrectDirection(fieldObjectType: NodeGraphQlFacade, relInfo0: RelationshipInfo, parent: NodeGraphQlFacade, relDirectiveField: RelationshipInfo?): RelationshipInfo {
        val startField = fieldObjectType.getFieldDefinition(relInfo0.startField)!!
        val endField = fieldObjectType.getFieldDefinition(relInfo0.endField)!!
        val startFieldTypeName = startField.type.inner().name
        val inverse = startFieldTypeName != parent.name() || startField.type == endField.type && relDirectiveField?.out != relInfo0.out
        return if (inverse) relInfo0.copy(out = relInfo0.out?.not(), startField = relInfo0.endField, endField = relInfo0.startField) else relInfo0
    }

    private fun projectRelationshipParent(variable: String, field: Field, fieldDefinition: GraphQLFieldDefinition, ctx: Context, variableSuffix: String?): Cypher {
        val fieldType = fieldDefinition.type
        val fieldObjectType = fieldType.inner()

        val fieldProjection = projectFields(variable + (variableSuffix?.capitalize()
                ?: ""), field, fieldObjectType, ctx, variableSuffix)

        return Cypher(fieldProjection.query)
    }

    private fun relDetails(type: NodeFacade, relDirective: Directive) =
            relDetails(type) { name, defaultValue -> relDirective.argumentString(name, schema, defaultValue) }

    class SkipLimit(variable: String,
            arguments: List<Argument>,
            private val skip: CypherArgument? = convertArgument(variable, arguments, "offset"),
            private val limit: CypherArgument? = convertArgument(variable, arguments, "first")) {

        fun format(): Cypher {
            return if (skip != null) {
                if (limit != null) Cypher(" SKIP $${skip.propertyName} LIMIT $${limit.propertyName}", mapOf(
                        skip.propertyName to skip.value,
                        limit.propertyName to limit.value)
                )
                else Cypher(" SKIP $${skip.propertyName}", mapOf(skip.propertyName to skip.value))
            } else {
                if (limit != null) Cypher(" LIMIT $${limit.propertyName}", mapOf(limit.propertyName to limit.value))
                else Cypher("")
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
                    else -> Cypher("")
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
            private fun convertArgument(variable: String, arguments: List<Argument>, name: String): CypherArgument? {
                val argument = arguments.find { it.name.toLowerCase() == name } ?: return null
                return CypherArgument(name, paramName(variable, argument.name, argument), argument.value?.toJavaValue())
            }
        }
    }


    private fun parse(query: String): Document {
        try {
            val parser = Parser()
            return parser.parseDocument(query)
        } catch (e: ParseCancellationException) {
            // todo proper structured error
            throw e
        }
    }
}


object SchemaBuilder {
    @JvmStatic
    fun buildSchema(sdl: String, ctx: Translator.Context = Translator.Context()): GraphQLSchema {
        val schemaParser = SchemaParser()
        val baseTypeDefinitionRegistry = schemaParser.parse(sdl)
        val augmentedTypeDefinitionRegistry = augmentSchema(baseTypeDefinitionRegistry, schemaParser, ctx)

        val builder = RuntimeWiring.newRuntimeWiring()
            .scalar(ObjectScalar())
            .type("Query") { it.dataFetcher("hello") { env -> "Hello ${env.getArgument<Any>("what")}!" } }
        baseTypeDefinitionRegistry
            .getTypes(InterfaceTypeDefinition::class.java)
            .forEach { typeDefinition -> builder.type(typeDefinition.name) { it.typeResolver { null } } }

        val runtimeWiring = builder.build()

        val schemaGenerator = SchemaGenerator()
        return schemaGenerator.makeExecutableSchema(augmentedTypeDefinitionRegistry, runtimeWiring)
            .transform { sc -> sc.build() } // todo add new queries, filters, enums etc.
    }

    private fun augmentSchema(typeDefinitionRegistry: TypeDefinitionRegistry, schemaParser: SchemaParser, ctx: Translator.Context): TypeDefinitionRegistry {
        val directivesSdl = javaClass.getResource("/neo4j_directives.graphql").readText()
        typeDefinitionRegistry.merge(schemaParser.parse(directivesSdl))

        val interfaceTypeDefinitions = typeDefinitionRegistry.types().values.filterIsInstance<InterfaceTypeDefinition>()
        val objectTypeDefinitions = typeDefinitionRegistry.types().values.filterIsInstance<ObjectTypeDefinition>()

        val nodeDefinitions: List<TypeDefinition<*>> = interfaceTypeDefinitions + objectTypeDefinitions

        val nodeMutations = nodeDefinitions.map { createNodeMutation(ctx, it.getNodeType()!!) }
        val relationTypes = objectTypeDefinitions
            .filter { it.getDirective(RELATION) != null }
            .map { it.getDirective(RELATION).getArgument(RELATION_NAME).value.toJavaValue().toString() to it }
            .toMap()

        val relMutations = objectTypeDefinitions.flatMap { source ->
            createRelationshipMutations(source, objectTypeDefinitions, relationTypes, ctx)
        } + relationTypes.values.map { createRelationshipTypeMutation(ctx, it, typeDefinitionRegistry) }.filterNotNull()

        val augmentations = nodeMutations + relMutations

        val augmentedTypesSdl = augmentations.flatMap { it -> listOf(it.filterType, it.ordering, it.inputType).filter { it.isNotBlank() } }.joinToString("\n")
        typeDefinitionRegistry.merge(schemaParser.parse(augmentedTypesSdl))

        val schemaDefinition = typeDefinitionRegistry.schemaDefinition().orElseGet { SchemaDefinition.newSchemaDefinition().build() }
        val operations = schemaDefinition.operationTypeDefinitions
            .associate { it.name to typeDefinitionRegistry.getType(it.typeName).orElseThrow { RuntimeException("Could not find type: " + it.typeName) } as ObjectTypeDefinition }
            .toMap()


        val queryDefinition = operations.getOrElse("query") {
            typeDefinitionRegistry.getType("Query", ObjectTypeDefinition::class.java).orElseGet {
                ObjectTypeDefinition("Query").also { typeDefinitionRegistry.add(it) }
            }
        }
        augmentations
            .filter { it.query.isNotBlank() && queryDefinition.fieldDefinitions.none { fd -> it.query.startsWith(fd.name + "(") } }
            .map { it.query }.let { it ->
                if (it.isNotEmpty()) {
                    val newQueries = schemaParser.parse("type AugmentedQuery { ${it.joinToString("\n")} }").getType("AugmentedQuery").get() as ObjectTypeDefinition
                    typeDefinitionRegistry.remove(queryDefinition)
                    typeDefinitionRegistry.add(queryDefinition.transform { qdb -> newQueries.fieldDefinitions.forEach { qdb.fieldDefinition(it) } })
                }
            }

        val mutationDefinition = operations.getOrElse("mutation") {
            typeDefinitionRegistry.getType("Mutation", ObjectTypeDefinition::class.java).orElseGet {
                ObjectTypeDefinition("Mutation").also { typeDefinitionRegistry.add(it) }
            }
        }
        augmentations.flatMap { it ->
            listOf(it.create, it.update, it.delete, it.merge)
                .filter { it.isNotBlank() && mutationDefinition.fieldDefinitions.none { fd -> it.startsWith(fd.name + "(") } }
        }
            .distinct() // for bidirectional mapping within an object there are multiple equal mutations
            .let { it ->
                if (it.isNotEmpty()) {
                    val newQueries = schemaParser.parse("type AugmentedMutation { ${it.joinToString("\n")} }").getType("AugmentedMutation").get() as ObjectTypeDefinition
                    typeDefinitionRegistry.remove(mutationDefinition)
                    typeDefinitionRegistry.add(mutationDefinition.transform { mdb -> newQueries.fieldDefinitions.forEach { mdb.fieldDefinition(it) } })
                }
            }

        val newSchemaDef = schemaDefinition.transform {
            it.operationTypeDefinition(OperationTypeDefinition("query", TypeName(queryDefinition.name)))
                .operationTypeDefinition(OperationTypeDefinition("mutation", TypeName(mutationDefinition.name))).build()
        }

        typeDefinitionRegistry.remove(schemaDefinition)
        typeDefinitionRegistry.add(newSchemaDef)

        return typeDefinitionRegistry
    }

    private fun createRelationshipMutations(
            source: ObjectTypeDefinition,
            objectTypeDefinitions: List<ObjectTypeDefinition>,
            relationTypes: Map<String, ObjectTypeDefinition>?,
            ctx: Translator.Context): List<Augmentation> {

        return source.fieldDefinitions
            .filter { !it.type.inner().isScalar() && it.getDirective(RELATION) != null }
            .mapNotNull { targetField ->
                objectTypeDefinitions.firstOrNull { it.name == targetField.type.inner().name() }
                    ?.let { target ->
                        createRelationshipMutation(ctx, source, target, relationTypes)
                    }
            }
    }
}