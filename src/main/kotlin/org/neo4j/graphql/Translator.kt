package org.neo4j.graphql

import graphql.Scalars
import graphql.introspection.Introspection
import graphql.language.*
import graphql.parser.Parser
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.antlr.v4.runtime.misc.ParseCancellationException
import java.lang.RuntimeException
import java.util.*

class Translator(val schema: GraphQLSchema) {
    data class Context @JvmOverloads constructor(val topLevelWhere: Boolean = true,
                                                 val fragments : Map<String,FragmentDefinition> = emptyMap(),
                                                 val temporal : Boolean = false,
                                                 val query: CRUDConfig = CRUDConfig(),
                                                 val mutation: CRUDConfig = CRUDConfig())
    data class CRUDConfig(val enabled:Boolean = true, val exclude: List<String> = emptyList())
    data class Cypher( val query: String, val params : Map<String,Any?> = emptyMap()) {
        companion object {
            val EMPTY = Cypher("")
        }
        fun with(p: Map<String,Any?>) = this.copy(params = this.params + p)
    }

    @JvmOverloads fun translate(query: String, params: Map<String, Any> = emptyMap(), context: Context = Context()) : List<Cypher> {
        val ast = parse(query) // todo preparsedDocumentProvider
        val ctx = context.copy(fragments = ast.definitions.filterIsInstance<FragmentDefinition>().map { it.name to it }.toMap())
        val queries = ast.definitions.filterIsInstance<OperationDefinition>()
                .filter { it.operation == OperationDefinition.Operation.QUERY } // todo variabledefinitions, directives, name
                .flatMap { it.selectionSet.selections }
                .filterIsInstance<Field>() // FragmentSpread, InlineFragment
                .map { toQuery(it, ctx).with(params) } // arguments, alias, directives, selectionSet
        return queries
    }

    private fun toQuery(queryField: Field, ctx:Context = Context()): Cypher {
        val name = queryField.name
        val queryType = schema.queryType.fieldDefinitions.filter { it.name == name }.firstOrNull() ?: throw IllegalArgumentException("Unknown Query $name available queries: " + schema.queryType.fieldDefinitions.map { it.name }.joinToString())
        val returnType = queryType.type.inner()
//        println(returnType)
        val type = schema.getType(returnType.name)
        val label = type.name.quote()
        val variable = queryField.aliasOrName().decapitalize()
        val mapProjection = projectFields(variable, queryField, type, ctx)
        val where = if (ctx.topLevelWhere) where(variable, queryType,  type, propertyArguments(queryField)) else Cypher.EMPTY
        val properties = if (ctx.topLevelWhere) Cypher.EMPTY else properties(variable, queryType, propertyArguments(queryField))
        val skipLimit = format(skipLimit(queryField.arguments))
        val ordering = orderBy(variable, queryField.arguments)
        return Cypher("MATCH ($variable:$label${properties.query})${where.query} RETURN ${mapProjection.query} AS $variable$ordering$skipLimit" ,
                (mapProjection.params + properties.params + where.params))
    }

    private fun propertyArguments(queryField: Field) =
            queryField.arguments.filterNot { listOf("first", "offset", "orderBy").contains(it.name) }

    private fun orderBy(variable: String, args: MutableList<Argument>): String {
        val arg = args.find { it.name == "orderBy" && it.value is ArrayValue }
        return if (arg == null) ""
        else " ORDER BY "+ (arg.value as ArrayValue).values.map { it.toJavaValue().toString().split("_") }
                .map { "$variable.${it[0]} ${it[1].toUpperCase()}"  }
                .joinToString(", ")
    }

    private fun where(variable: String, field: GraphQLFieldDefinition, type: GraphQLType, arguments: List<Argument>) : Cypher {
        val all = prepareArguments(field, arguments)
        if (all.isEmpty()) return Cypher("")
        val (filterExpressions, filterParams) =
            filterExpressions(all.find{ it.name == "filter" }?.value, type as GraphQLObjectType)
                    .map { it.toExpression(variable, schema) }
                    .let { expressions ->
                        expressions.map { it.first } to expressions.fold(emptyMap<String,Any?>()) { res, exp -> res + exp.second }
                    }
        val noFilter = all.filter { it.name != "filter" }
        // todo turn it into a Predicate too
        val eqExpression = noFilter.map { (k, p, v) -> "$variable.${p.quote()} = \$${paramName(variable, k, v)}" }
        val expression = (eqExpression + filterExpressions).joinNonEmpty(" AND ") // TODO talk to Will ,"(",")")
        return Cypher(" WHERE $expression", filterParams + noFilter.map { (k,_,v) -> paramName(variable, k, v) to v }.toMap()
        )
    }

    private fun filterExpressions(value: Any?, type: GraphQLObjectType): List<Predicate> {
        // todo variable/parameter
        return if (value is Map<*,*>) {
            CompoundPredicate(value.map { (k, v) -> toExpression(k.toString(), v, type) }, "AND").parts
        }
        else emptyList()
    }

    private fun properties(variable: String, field: GraphQLFieldDefinition, arguments: List<Argument>) : Cypher {
        val all = prepareArguments(field, arguments)
        return Cypher(all.map { (k,p, v) -> "${p.quote()}:\$${paramName(variable, k, v)}"}.joinToString(" , "," {","}") ,
               all.map { (k,p,v) -> paramName(variable, k, v) to v }.toMap())
    }

    data class CypherArgument(val name:String, val propertyName:String, val value:Any?)

    private fun prepareArguments(field: GraphQLFieldDefinition, arguments: List<Argument>): List<CypherArgument> {
        if (arguments.isEmpty()) return emptyList()
        val resultObjectType = schema.getType(field.type.inner().name) as GraphQLObjectType
        val predicates = arguments.map { it.name to CypherArgument(it.name, resultObjectType.getFieldDefinition(it.name)?.propertyDirectiveName() ?: it.name, it.value.toJavaValue()) }.toMap()
        val defaults = field.arguments.filter { it.defaultValue != null && !predicates.containsKey(it.name) }
                .map { CypherArgument(it.name, it.name, it.defaultValue) }
        return predicates.values + defaults
    }

    private fun projectFields(variable: String, field: Field, type: GraphQLType, ctx: Context): Cypher {
        // todo handle non-object case
        val objectType = type as GraphQLObjectType
        val properties = field.selectionSet.selections.flatMap {
            when (it) {
                is Field -> listOf(projectField(variable, it, objectType, ctx))
                is InlineFragment -> projectInlineFragment(variable, it, objectType, ctx)
                is FragmentSpread -> projectNamedFragments(variable, it, objectType, ctx)
                else -> emptyList()
            }
        }

        val projection = properties.map { it.query }.joinToString(",", "{ ", " }")
        val params = properties.map{ it.params }.fold(emptyMap<String,Any?>()) { res, map -> res + map }
        return Cypher("$variable $projection",params)
    }

    private fun projectField(variable: String, field: Field, type: GraphQLObjectType, ctx:Context) : Cypher {
        val fieldDefinition = type.getFieldDefinition(field.name) ?: throw IllegalStateException("No field ${field.name} in ${type.name}")
        return if (fieldDefinition.type.inner() is GraphQLObjectType) {
            val patternComprehensions = projectRelationship(variable, field, fieldDefinition, type, ctx)
            Cypher(field.aliasOrName() + ":" + patternComprehensions.query, patternComprehensions.params)
        } else
            if (field.aliasOrName() == field.propertyName(fieldDefinition))
                Cypher("." + field.propertyName(fieldDefinition))
            else
                Cypher(field.aliasOrName() + ":" + variable+"."+field.propertyName(fieldDefinition))
    }

    fun projectNamedFragments(variable: String, fragmentSpread: FragmentSpread, type: GraphQLObjectType, ctx: Context) =
            ctx.fragments.getValue(fragmentSpread.name).let {
                projectFragment(it.typeCondition.name, type, variable, ctx, it.selectionSet)
            }

    private fun projectFragment(fragmentTypeName: String?, type: GraphQLObjectType, variable: String, ctx: Context, selectionSet: SelectionSet): List<Cypher> {
        val fragmentType = schema.getType(fragmentTypeName)!! as GraphQLObjectType
        if (fragmentType == type) {
            // these are the nested fields of the fragment
            // it could be that we have to adapt the variable name too, and perhaps add some kind of rename
            return selectionSet.selections.filterIsInstance<Field>().map { projectField(variable, it, fragmentType, ctx) }
        } else {
            return emptyList()
        }
    }

    fun projectInlineFragment(variable: String, fragment: InlineFragment, type: GraphQLObjectType, ctx: Context) =
            projectFragment(fragment.typeCondition.name, type, variable, ctx, fragment.selectionSet)


    private fun projectRelationship(variable: String, field: Field, fieldDefinition: GraphQLFieldDefinition, parent: GraphQLObjectType, ctx: Context): Cypher {
        val parentIsRelationship = parent.definition.directivesByName.containsKey("relation")
        return when (parentIsRelationship) {
            true -> projectRelationshipParent(variable, field, fieldDefinition, parent, ctx)
            else -> projectRichAndRegularRelationship(variable, field, fieldDefinition, ctx)
        }
    }

    private fun projectRichAndRegularRelationship(variable: String, field: Field, fieldDefinition: GraphQLFieldDefinition, ctx: Context): Cypher {
        val fieldType = fieldDefinition.type
        val fieldObjectType = fieldType.inner() as GraphQLObjectType
        // todo combine both nestings if rel-entity
        val (relDirective, relFromType) = fieldObjectType.definition.getDirective("relation")?.let { it to true }
                ?: fieldDefinition.definition.getDirective("relation")?.let { it to false }
                ?: throw IllegalStateException("Field $field needs an @relation directive")

        val (relType, outgoing, endField) = relDetails(relDirective)
        val (inArrow, outArrow) = arrows(outgoing)

        val childVariable = variable + field.name.capitalize()

        val endNodePattern = when {
            relFromType -> {
                val label = fieldObjectType.getFieldDefinition(endField).type.inner().name
                "$childVariable${endField.capitalize()}:$label"
            }
            else -> "$childVariable:${fieldObjectType.name}"
        }

        val relPattern = if (relFromType) "$childVariable:${relType}" else ":${relType}"

        val where = where(childVariable, fieldDefinition, fieldObjectType, propertyArguments(field))
        val fieldProjection = projectFields(childVariable, field, fieldObjectType, ctx)

        val comprehension = "[($variable)$inArrow-[$relPattern]-$outArrow($endNodePattern)${where.query} | ${fieldProjection.query}]"
        val skipLimit = skipLimit(field.arguments)
        val slice = slice(skipLimit, fieldType.isList())
        return Cypher(comprehension + slice, (where.params + fieldProjection.params))
    }

    private fun projectRelationshipParent(variable: String, field: Field, fieldDefinition: GraphQLFieldDefinition, parent: GraphQLObjectType, ctx: Context): Cypher {
        val fieldType = fieldDefinition.type
        val fieldObjectType = fieldType.inner() as GraphQLObjectType
        val relDirective = parent.definition.directivesByName.getValue("relation")
        val (_, _, endField) = relDetails(relDirective)

        val fieldProjection = projectFields(variable + endField.capitalize(), field, fieldObjectType, ctx)

        return Cypher(fieldProjection.query)
    }

    private fun relDetails(relDirective: Directive) = relDetails(relDirective, schema)

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


    private fun toCypherString(value:Any, type: GraphQLType ): String = when (value) {
        is String -> "'"+value+"'"
        else -> value.toString()
    }

    private fun parse(query:String): Document {
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
    @JvmStatic fun buildSchema(sdl: String, ctx: Translator.Context = Translator.Context() ) : GraphQLSchema {
        val schemaParser = SchemaParser()
        val typeDefinitionRegistry = augmentSchema(schemaParser.parse(sdl), schemaParser, ctx)

        val runtimeWiring = RuntimeWiring.newRuntimeWiring()
                .type("Query")
                { it.dataFetcher("hello") { env -> "Hello ${env.getArgument<Any>("what")}!" } }
                .build()

        val schemaGenerator = SchemaGenerator()
        return schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
                .transform { bc -> bc.additionalDirectives(additionalDirectives()).build() }
                .transform { sc -> sc.build() } // todo add new queries, filters, enums etc.
    }

    private fun additionalDirectives(): Set<GraphQLDirective> {
        val directives = setOf(
                GraphQLDirective.newDirective().name("property").description("additional metadata per field")
                        .argument{ b -> b.name("name").description("name of the graph property").type(Scalars.GraphQLString)}.build(),
                GraphQLDirective("relation", "relation directive",
                EnumSet.of(Introspection.DirectiveLocation.FIELD, Introspection.DirectiveLocation.OBJECT),
                listOf(GraphQLArgument("name", Scalars.GraphQLString),
                        GraphQLArgument("direction", "relationship direction", Scalars.GraphQLString, "OUT"),
                        GraphQLArgument("from", "from field name", Scalars.GraphQLString, "from"),
                        GraphQLArgument("to", "to field name", Scalars.GraphQLString, "to")), false, false, true))
        return directives
    }

    private fun augmentSchema(typeDefinitionRegistry: TypeDefinitionRegistry, schemaParser: SchemaParser, ctx: Translator.Context): TypeDefinitionRegistry {

        val augmentations = typeDefinitionRegistry.types().values.filterIsInstance<ObjectTypeDefinition>().map { augmentedSchema(ctx, it) }

        val augmentedTypesSdl = augmentations.flatMap { listOf(it.filterType, it.ordering, it.inputType).filter { it.isNotBlank() } }.joinToString("\n")
        typeDefinitionRegistry.merge(schemaParser.parse(augmentedTypesSdl))

        val schemaDefinition = typeDefinitionRegistry.schemaDefinition().orElseGet { SchemaDefinition() }
        if (!typeDefinitionRegistry.schemaDefinition().isPresent) typeDefinitionRegistry.add(schemaDefinition).ifPresent { throw RuntimeException(it.toSpecification().toString()) }

        val operations = schemaDefinition.operationTypeDefinitions.associate { it.name to typeDefinitionRegistry.getType(it.type).get() as ObjectTypeDefinition }.toMap()

        val queryDefinition = operations.getOrElse("query") {
            ObjectTypeDefinition("Query").also {
                schemaDefinition.operationTypeDefinitions.add(OperationTypeDefinition("query",TypeName(it.name)))
                typeDefinitionRegistry.add(it)
            }
        }
        val mutationDefinition = operations.getOrElse("mutation") {
                ObjectTypeDefinition("Mutation").also { schemaDefinition.operationTypeDefinitions.add(OperationTypeDefinition("mutation",TypeName(it.name)))
                typeDefinitionRegistry.add(it)
            }
        }

        augmentations.filter { it.query.isNotBlank() }.map { it.query }.let {
            if (!it.isEmpty()) {
                val newQueries = schemaParser.parse("type AugmentedQuery { ${it.joinToString("\n")} }").getType("AugmentedQuery").get() as ObjectTypeDefinition
                queryDefinition.fieldDefinitions.addAll(newQueries.fieldDefinitions)
            }
        }
        augmentations.flatMap { listOf(it.create,it.update,it.delete).filter{ it.isNotBlank() }}.let {
            if (!it.isEmpty()) {
                val newQueries = schemaParser.parse("type AugmentedMutation { ${it.joinToString("\n")} }").getType("AugmentedMutation").get() as ObjectTypeDefinition
                mutationDefinition.fieldDefinitions.addAll(newQueries.fieldDefinitions)
            }
        }

        return typeDefinitionRegistry
    }
}