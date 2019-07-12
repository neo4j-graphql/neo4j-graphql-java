package org.neo4j.graphql

import graphql.Scalars
import graphql.language.*
import graphql.parser.Parser
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.antlr.v4.runtime.misc.ParseCancellationException
import java.math.BigDecimal
import java.math.BigInteger

class Translator(val schema: GraphQLSchema) {
    data class Context @JvmOverloads constructor(val topLevelWhere: Boolean = true,
            val fragments: Map<String, FragmentDefinition> = emptyMap(),
            val temporal: Boolean = false,
            val query: CRUDConfig = CRUDConfig(),
            val mutation: CRUDConfig = CRUDConfig())

    data class CRUDConfig(val enabled: Boolean = true, val exclude: List<String> = emptyList())
    data class Cypher(val query: String, val params: Map<String, Any?> = emptyMap()) {
        fun with(p: Map<String, Any?>) = this.copy(params = this.params + p)
        fun escapedQuery() = query.replace("\"", "\\\"").replace("'", "\\'")

        companion object {
            val EMPTY = Cypher("")

            private fun findRelNodeId(objectType: GraphQLObjectType) = objectType.fieldDefinitions.find { it.isID() }!!

            private fun createRelStatement(source: GraphQLType, target: GraphQLFieldDefinition,
                    keyword: String = "MERGE"): String {
                val innerTarget = target.type.inner()
                val relationshipDirective = target.getDirective("relation")
                        ?: throw IllegalArgumentException("Missing @relation directive for relation ${target.name}")
                val targetFilterType = if (target.type.isList()) "IN" else "="
                val sourceId = findRelNodeId(source as GraphQLObjectType)
                val targetId = findRelNodeId(innerTarget as GraphQLObjectType)
                val (left, right) = if (relationshipDirective.getRelationshipDirection() == "OUT") ("" to ">") else ("<" to "")
                return "MATCH (from:${source.name.quote()} {${sourceId.name.quote()}:$${sourceId.name}}) " +
                        "MATCH (to:${innerTarget.name.quote()}) WHERE to.${targetId.name.quote()} $targetFilterType $${target.name} " +
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
        val returnType = fieldDefinition.type.inner()
//        println(returnType)
        val type = schema.getType(returnType.name)
        val label = type.name.quote()
        val variable = field.aliasOrName().decapitalize()
        val cypherDirective = fieldDefinition.cypherDirective()
        val mapProjection = projectFields(variable, field, type, ctx, null)
        val skipLimit = format(skipLimit(field.arguments))
        val ordering = orderBy(variable, field.arguments)
        if (cypherDirective != null) {
            // todo filters and such from nested fields
            return cypherQueryOrMutation(variable, fieldDefinition, field, cypherDirective, mapProjection, ordering, skipLimit, isQuery)

        } else {
            if (isQuery) {
                val where = if (ctx.topLevelWhere) where(variable, fieldDefinition, type, propertyArguments(field)) else Cypher.EMPTY
                val properties = if (ctx.topLevelWhere) Cypher.EMPTY else properties(variable, fieldDefinition, propertyArguments(field))
                return Cypher("MATCH ($variable:$label${properties.query})${where.query} RETURN ${mapProjection.query} AS $variable$ordering$skipLimit",
                        (mapProjection.params + properties.params + where.params))
            } else {
                // TODO add into Cypher companion object as did for the relationships
                val properties = properties(variable, fieldDefinition, propertyArguments(field))
                val idProperty = fieldDefinition.arguments.find { it.type.inner() == Scalars.GraphQLID }
                val returnStatement = "WITH $variable RETURN ${mapProjection.query} AS $variable$ordering$skipLimit"
                return when (name) {
                    "create" + type.name -> Cypher("CREATE ($variable:$label${properties.query}) " + returnStatement, (mapProjection.params + properties.params))
                    "merge" + type.name -> {
                        val setProperties = setProperties(variable, fieldDefinition, propertyArguments(field), listOf(idProperty!!.name))
                        Cypher("MERGE ($variable:$label {${idProperty.name.quote()}:\$${paramName(variable, idProperty.name, properties.params[idProperty.name])}})"
                                + setProperties.query + returnStatement,
                                (mapProjection.params + properties.params))
                    }
                    "update" + type.name -> {
                        val setProperties = setProperties(variable, fieldDefinition, propertyArguments(field))
                        Cypher("MATCH ($variable:$label {${idProperty!!.name.quote()}:\$${paramName(variable, idProperty.name, properties.params[idProperty.name])}}) " + setProperties.query + returnStatement,
                                (mapProjection.params + setProperties.params))
                    }
                    "delete" + type.name -> {
                        val paramName = paramName(variable, idProperty!!.name, properties.params[idProperty.name]) // todo currently wrong, needs to be paramName
                        Cypher("MATCH ($variable:$label {${idProperty.name.quote()}:\$$paramName}) " +
                                "WITH $variable as toDelete, ${mapProjection.query} AS $variable $ordering$skipLimit DETACH DELETE toDelete RETURN $variable",
                                (mapProjection.params + mapOf(paramName to properties.params[paramName])))
                    }
                    else -> checkRelationships(fieldDefinition, field, ordering, skipLimit, ctx)
                }
            }
        }
    }

    private fun checkRelationships(sourceFieldDefinition: GraphQLFieldDefinition, field: Field, ordering: String, skipLimit: String, ctx: Context): Cypher {
        val source = sourceFieldDefinition.type as GraphQLObjectType
        val targetFieldDefinition = filterTarget(source, field, sourceFieldDefinition)

        val sourceVariable = "from"
        val mapProjection = projectFields(sourceVariable, field, source, ctx, null)
        val returnStatement = "WITH DISTINCT $sourceVariable RETURN ${mapProjection.query} AS ${source.name.decapitalize().quote()}$ordering$skipLimit"
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
            it.copy(query = it.query + returnStatement, params = properties)
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

    private fun cypherQueryOrMutation(variable: String, fieldDefinition: GraphQLFieldDefinition, field: Field, cypherDirective: Cypher, mapProjection: Cypher, ordering: String, skipLimit: String, isQuery: Boolean) =
            if (isQuery) {
                val (query, params) = cypherDirective(variable, fieldDefinition, field, cypherDirective, emptyList())
                Cypher("UNWIND $query AS $variable RETURN ${mapProjection.query} AS $variable$ordering$skipLimit",
                        (params + mapProjection.params))
            } else {
                val (query, params) = cypherDirectiveQuery(variable, fieldDefinition, field, cypherDirective, emptyList())
                Cypher("CALL apoc.cypher.doIt($query) YIELD value WITH value[head(keys(value))] AS $variable RETURN ${mapProjection.query} AS $variable$ordering$skipLimit",
                        (params + mapProjection.params))
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

    private fun where(variable: String, field: GraphQLFieldDefinition, type: GraphQLType, arguments: List<Argument>): Cypher {
        val all = preparePredicateArguments(field, arguments).filterNot { listOf("first", "offset", "orderBy").contains(it.name) }
        if (all.isEmpty()) return Cypher("")
        val (filterExpressions, filterParams) =
                filterExpressions(all.find { it.name == "filter" }?.value, type as GraphQLObjectType)
                    .map { it.toExpression(variable, schema) }
                    .let { expressions ->
                        expressions.map { it.first } to expressions.fold(emptyMap<String, Any?>()) { res, exp -> res + exp.second }
                    }
        val noFilter = all.filter { it.name != "filter" }
        // todo turn it into a Predicate too
        val eqExpression = noFilter.map { (k, p, v) -> "$variable.${p.quote()} = \$${paramName(variable, k, v)}" }
        val expression = (eqExpression + filterExpressions).joinNonEmpty(" AND ") // TODO talk to Will ,"(",")")
        return Cypher(" WHERE $expression", filterParams + noFilter.map { (k, _, v) -> paramName(variable, k, v) to v }.toMap()
        )
    }

    private fun filterExpressions(value: Any?, type: GraphQLObjectType): List<Predicate> {
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
            excludeProperties: List<String> = emptyList()): Cypher {
        val all = if (excludeProperties.isNotEmpty()) {
            preparePredicateArguments(field, arguments).filter { !excludeProperties.contains(it.name) }
        } else {
            preparePredicateArguments(field, arguments)
        }
        return Cypher(all.joinToString(",", " SET ", " ") { (k, p, v) -> "${variable.quote()}.${p.quote()}=\$${paramName(variable, k, v)}" },
                all.map { (k, _, v) -> paramName(variable, k, v) to v }.toMap())
    }

    data class CypherArgument(val name: String, val propertyName: String, val value: Any?)

    private fun preparePredicateArguments(field: GraphQLFieldDefinition, arguments: List<Argument>): List<CypherArgument> {
        if (arguments.isEmpty()) return emptyList()
        val resultObjectType = schema.getType(field.type.inner().name) as GraphQLObjectType
        val predicates = arguments.map {
            it.name to CypherArgument(it.name, resultObjectType.getFieldDefinition(it.name)?.propertyDirectiveName()
                    ?: it.name, it.value.toJavaValue())
        }.toMap()
        val defaults = field.arguments.filter { it.defaultValue != null && !predicates.containsKey(it.name) }
            .map { CypherArgument(it.name, it.name, it.defaultValue) }
        return predicates.values + defaults
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
        val objectType = type as GraphQLObjectType
        val properties = field.selectionSet.selections.flatMap {
            when (it) {
                is Field -> listOf(projectField(variable, it, objectType, ctx, variableSuffix))
                is InlineFragment -> projectInlineFragment(variable, it, objectType, ctx, variableSuffix)
                is FragmentSpread -> projectNamedFragments(variable, it, objectType, ctx, variableSuffix)
                else -> emptyList()
            }
        }

        val projection = properties.joinToString(",", "{ ", " }") { it.query }
        val params = properties.map { it.params }.fold(emptyMap<String, Any?>()) { res, map -> res + map }
        return Cypher("$variable $projection", params)
    }

    private fun projectField(variable: String, field: Field, type: GraphQLObjectType, ctx: Context, variableSuffix: String?): Cypher {
        val fieldDefinition = type.getFieldDefinition(field.name)
                ?: throw IllegalStateException("No field ${field.name} in ${type.name}")
        val cypherDirective = fieldDefinition.cypherDirective()
        val isObjectField = fieldDefinition.type.inner() is GraphQLObjectType
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
            field.aliasOrName() == field.propertyName(fieldDefinition) -> Cypher("." + field.propertyName(fieldDefinition))
            else -> Cypher(field.aliasOrName() + ":" + variable + "." + field.propertyName(fieldDefinition))
        }
    }

    private fun cypherDirective(variable: String, fieldDefinition: GraphQLFieldDefinition, field: Field, cypherDirective: Cypher, additionalArgs: List<CypherArgument>): Cypher {
        val suffix = if (fieldDefinition.type.isList()) "Many" else "Single"
        val (query, args) = cypherDirectiveQuery(variable, fieldDefinition, field, cypherDirective, additionalArgs)
        return Cypher("apoc.cypher.runFirstColumn$suffix($query)", args)
    }

    private fun cypherDirectiveQuery(variable: String, fieldDefinition: GraphQLFieldDefinition, field: Field, cypherDirective: Cypher, additionalArgs: List<CypherArgument>): Cypher {
        val args = additionalArgs + prepareFieldArguments(fieldDefinition, field.arguments)
        val argParams = args.map { '$' + it.name + " AS " + it.name }.joinNonEmpty(",")
        val query = (if (argParams.isEmpty()) "" else "WITH $argParams ") + cypherDirective.escapedQuery()
        val argString = (args.map { it.name + ':' + if (it.name == "this") it.value else ('$' + paramName(variable, it.name, it.value)) }).joinToString(",", "{", "}")
        return Cypher("'$query',$argString", args.filter { it.name != "this" }.associate { paramName(variable, it.name, it.value) to it.value })
    }

    private fun projectNamedFragments(variable: String, fragmentSpread: FragmentSpread, type: GraphQLObjectType, ctx: Context, variableSuffix: String?) =
            ctx.fragments.getValue(fragmentSpread.name).let {
                projectFragment(it.typeCondition.name, type, variable, ctx, variableSuffix, it.selectionSet)
            }

    private fun projectFragment(fragmentTypeName: String?, type: GraphQLObjectType, variable: String, ctx: Context, variableSuffix: String?, selectionSet: SelectionSet): List<Cypher> {
        val fragmentType = schema.getType(fragmentTypeName)!! as GraphQLObjectType
        return if (fragmentType == type) {
            // these are the nested fields of the fragment
            // it could be that we have to adapt the variable name too, and perhaps add some kind of rename
            selectionSet.selections.filterIsInstance<Field>().map { projectField(variable, it, fragmentType, ctx, variableSuffix) }
        } else {
            emptyList()
        }
    }

    private fun projectInlineFragment(variable: String, fragment: InlineFragment, type: GraphQLObjectType, ctx: Context, variableSuffix: String?) =
            projectFragment(fragment.typeCondition.name, type, variable, ctx, variableSuffix, fragment.selectionSet)


    private fun projectRelationship(variable: String, field: Field, fieldDefinition: GraphQLFieldDefinition, parent: GraphQLObjectType, ctx: Context, variableSuffix: String?): Cypher {
        return when (parent.definition.directivesByName.containsKey("relation")) {
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
        val skipLimit = skipLimit(field.arguments)
        val slice = slice(skipLimit, fieldType.isList())
        return Cypher(comprehension + slice, (expression.params + fieldProjection.params)) // + where.params

    }

    private fun projectRichAndRegularRelationship(variable: String, field: Field, fieldDefinition: GraphQLFieldDefinition, parent: GraphQLObjectType, ctx: Context): Cypher {
        val fieldType = fieldDefinition.type
        val fieldObjectType = fieldType.inner() as GraphQLObjectType
        // todo combine both nestings if rel-entity
        val relDirectiveObject = fieldObjectType.definition.getDirective("relation")?.let { relDetails(fieldObjectType, it) }
        val relDirectiveField = fieldDefinition.definition.getDirective("relation")?.let { relDetails(fieldObjectType, it) }

        val (relInfo0, isRelFromType) =
                relDirectiveObject?.let { it to true }
                        ?: relDirectiveField?.let { it to false }
                        ?: throw IllegalStateException("Field $field needs an @relation directive")

        val relInfo = if (isRelFromType) relationshipInfoInCorrectDirection(fieldObjectType, relInfo0, parent, relDirectiveField) else relInfo0

        val (inArrow, outArrow) = relInfo.arrows

        val childVariable = variable + field.name.capitalize()

        val (endNodePattern, variableSuffix) = when {
            isRelFromType -> {
                val label = fieldObjectType.getFieldDefinition(relInfo.endField!!).type.inner().name
                ("$childVariable${relInfo.endField.capitalize()}:$label" to relInfo.endField)
            }
            else -> ("$childVariable:${fieldObjectType.name}" to null)
        }

        val relPattern = if (isRelFromType) "$childVariable:${relInfo.type}" else ":${relInfo.type}"

        val where = where(childVariable, fieldDefinition, fieldObjectType, propertyArguments(field))
        val fieldProjection = projectFields(childVariable, field, fieldObjectType, ctx, variableSuffix)

        val comprehension = "[($variable)$inArrow-[$relPattern]-$outArrow($endNodePattern)${where.query} | ${fieldProjection.query}]"
        val skipLimit = skipLimit(field.arguments)
        val slice = slice(skipLimit, fieldType.isList())
        return Cypher(comprehension + slice, (where.params + fieldProjection.params))
    }

    private fun relationshipInfoInCorrectDirection(fieldObjectType: GraphQLObjectType, relInfo0: RelationshipInfo, parent: GraphQLObjectType, relDirectiveField: RelationshipInfo?): RelationshipInfo {
        val startField = fieldObjectType.getFieldDefinition(relInfo0.startField)
        val endField = fieldObjectType.getFieldDefinition(relInfo0.endField)
        val startFieldTypeName = startField.type.inner().name
        val inverse = startFieldTypeName != parent.name || startField.type == endField.type && relDirectiveField?.out != relInfo0.out
        return if (inverse) relInfo0.copy(out = relInfo0.out?.not(), startField = relInfo0.endField, endField = relInfo0.startField) else relInfo0
    }

    private fun projectRelationshipParent(variable: String, field: Field, fieldDefinition: GraphQLFieldDefinition, ctx: Context, variableSuffix: String?): Cypher {
        val fieldType = fieldDefinition.type
        val fieldObjectType = fieldType.inner() as GraphQLObjectType

        val fieldProjection = projectFields(variable + variableSuffix!!.capitalize(), field, fieldObjectType, ctx, variableSuffix)

        return Cypher(fieldProjection.query)
    }

    private fun relDetails(target: GraphQLObjectType, relDirective: Directive) = relDetails(target, relDirective, schema)

    private fun slice(skipLimit: Pair<Int, Int>, list: Boolean = false) =
            if (list) {
                if (skipLimit.first == 0 && skipLimit.second == -1) ""
                else if (skipLimit.second == -1) "[${skipLimit.first}..]"
                else "[${skipLimit.first}..${skipLimit.first + skipLimit.second}]"
            } else "[${skipLimit.first}]"

    private fun format(skipLimit: Pair<Int, Int>) =
            if (skipLimit.first > 0) {
                if (skipLimit.second > -1) " SKIP ${skipLimit.first} LIMIT ${skipLimit.second}"
                else " SKIP ${skipLimit.first}"
            } else {
                if (skipLimit.second > -1) " LIMIT ${skipLimit.second}"
                else ""
            }

    private fun skipLimit(arguments: List<Argument>): Pair<Int, Int> {
        val limit = numericArgument(arguments, "first", -1).toInt()
        val skip = numericArgument(arguments, "offset").toInt()
        return skip to limit
    }

    private fun numericArgument(arguments: List<Argument>, name: String, defaultValue: Number = 0) =
            (arguments.find { it.name.toLowerCase() == name }?.value?.toJavaValue() as Number?) ?: defaultValue


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

        val runtimeWiring = RuntimeWiring.newRuntimeWiring()
            .type("Query") { it.dataFetcher("hello") { env -> "Hello ${env.getArgument<Any>("what")}!" } }
            .build()

        val schemaGenerator = SchemaGenerator()
        return schemaGenerator.makeExecutableSchema(augmentedTypeDefinitionRegistry, runtimeWiring)
            .transform { sc -> sc.build() } // todo add new queries, filters, enums etc.
    }

    private fun augmentSchema(typeDefinitionRegistry: TypeDefinitionRegistry, schemaParser: SchemaParser, ctx: Translator.Context): TypeDefinitionRegistry {
        val directivesSdl = """
            enum RelationDirection {
               IN
               OUT
               BOTH
            }
            directive @relation(name:String, direction: RelationDirection = OUT, from: String = "from", to: String = "to") on FIELD_DEFINITION | OBJECT
            directive @cypher(statement:String) on FIELD_DEFINITION
            directive @property(name:String) on FIELD_DEFINITION
        """
        typeDefinitionRegistry.merge(schemaParser.parse(directivesSdl))

        val objectTypeDefinitions = typeDefinitionRegistry.types().values.filterIsInstance<ObjectTypeDefinition>()
        val nodeMutations = objectTypeDefinitions.map { createNodeMutation(ctx, it) }
        val relMutations = objectTypeDefinitions.flatMap { source ->
            createRelationshipMutations(source, objectTypeDefinitions, ctx)
        }
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

    private fun createRelationshipMutations(source: ObjectTypeDefinition,
            objectTypeDefinitions: List<ObjectTypeDefinition>,
            ctx: Translator.Context): List<Augmentation> = source.fieldDefinitions
        .filter { !it.type.inner().isScalar() && it.getDirective("relation") != null }
        .mapNotNull { targetField ->
            objectTypeDefinitions.firstOrNull { it.name == targetField.type.inner().name() }
                ?.let { target ->
                    createRelationshipMutation(ctx, source, target)
                }
        }
}