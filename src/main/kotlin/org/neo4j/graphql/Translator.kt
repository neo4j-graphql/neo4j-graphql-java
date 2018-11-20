package org.neo4j.graphql

import graphql.Scalars
import graphql.introspection.Introspection
import graphql.language.*
import graphql.parser.Parser
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.antlr.v4.runtime.misc.ParseCancellationException
import java.util.*

class Translator(val schema: GraphQLSchema) {
    data class Context(val topLevelWhere: Boolean = true, val fragments : Map<String,FragmentDefinition> = emptyMap())
    data class Cypher( val query: String, val params : Map<String,Any?> = emptyMap()) {
        companion object {
            val EMPTY = Cypher("")
        }
        fun with(p: Map<String,Any?>) = this.copy(params = this.params + p)
    }

    fun translate(query: String, params: Map<String, Any> = emptyMap(), context: Context = Context()) : List<Cypher> {
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
        val returnType = inner(queryType.type)
//        println(returnType)
        val type = schema.getType(returnType.name)
        val label = type.name.quote()
        val variable = queryField.aliasOrName().decapitalize()
        val mapProjection = projectFields(variable, queryField, type, ctx)
        val where = if (ctx.topLevelWhere) where(variable, queryType, propertyArguments(queryField)) else Cypher.EMPTY
        val properties = if (ctx.topLevelWhere) Cypher.EMPTY else properties(variable, queryType, propertyArguments(queryField))
        val skipLimit = format(skipLimit(queryField.arguments))
        val ordering = orderBy(variable, queryField.arguments)
        return Cypher("MATCH ($variable:$label${properties.query})${where.query} RETURN ${mapProjection.query} AS $variable$ordering$skipLimit" ,
                (mapProjection.params + properties.params + where.params))
    }

    private fun propertyArguments(queryField: Field) =
            queryField.arguments.filterNot { listOf("first", "offset", "orderBy", "filter").contains(it.name) }

    private fun orderBy(variable: String, args: MutableList<Argument>): String {
        val arg = args.find { it.name == "orderBy" && it.value is ArrayValue }
        return if (arg == null) ""
        else " ORDER BY "+ (arg.value as ArrayValue).values.map { it.toJavaValue().toString().split("_") }
                .map { "$variable.${it[0]} ${it[1].toUpperCase()}"  }
                .joinToString(", ")
    }

    private fun where(variable: String, field: GraphQLFieldDefinition, arguments: List<Argument>) : Cypher {
        val all = prepareArguments(field, arguments)
        if (all.isEmpty()) return Cypher("")
        return Cypher(" WHERE "+all.map { (k,v) -> "$variable.$k = \$${paramName(variable, k, v)}"}.joinToString(" AND ") ,
                all.map { (k,v) -> paramName(variable, k, v) to v }.toMap()
        )
    }

    private fun paramName(variable: String, argName: String, value: Any?) = when (value) {
        is VariableReference ->  value.name
        else -> "$variable${argName.capitalize()}"
    }

    private fun properties(variable: String, field: GraphQLFieldDefinition, arguments: List<Argument>) : Cypher {
        val all = prepareArguments(field, arguments)
        return Cypher(all.map { (k,v) -> "$k:\$${paramName(variable, k, v)}"}.joinToString(" , "," {","}") ,
               all.map { (k,v) -> paramName(variable, k, v) to v }.toMap())
    }

    private fun prepareArguments(field: GraphQLFieldDefinition, arguments: List<Argument>): Map<String, Any?> {
        if (arguments.isEmpty()) return emptyMap()
        val predicates = arguments.map { it.name to it.value.toJavaValue() }.toMap() // .toCypherString()
        val defaults = field.arguments.filter { it.defaultValue != null && !predicates.containsKey(it.name) }.map { it.name to it.defaultValue } // toCypherString(it.defaultValue, it.type)
        return predicates + defaults
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
        return if (inner(fieldDefinition.type) is GraphQLObjectType) {
            val patternComprehensions = projectRelationship(variable, field, fieldDefinition, type, ctx)
            Cypher(field.aliasOrName() + ":" + patternComprehensions.query, patternComprehensions.params)
        } else Cypher("." + field.aliasOrName())
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


    private fun projectRelationship(variable: String, field: Field, fieldDefinition: GraphQLFieldDefinition, parent:GraphQLObjectType, ctx:Context): Cypher {
        val fieldType = fieldDefinition.type
        val innerType = inner(fieldType).name
        val fieldObjectType = inner(fieldType) as GraphQLObjectType // schema.getType(innerType) as GraphQLObjectType
        val parentIsRelationship = parent.definition.directivesByName.containsKey("relation")
        // todo combine both if rel-entity
        if (!parentIsRelationship) {
            val (relDirective, relFromType) = fieldObjectType.definition.getDirective("relation")?.let { it to true }
                    ?: fieldDefinition.definition.getDirective("relation")?.let { it to false }
                    ?: throw IllegalStateException("Field $field needs an @relation directive")

            val (relType, outgoing, endField) = relDetails(relDirective)
            val (inArrow, outArrow) = arrows(outgoing)

            val childVariable = variable + field.name.capitalize() // + fieldObjectType.name
            val endNodePattern = if (relFromType) {
                val label = inner(fieldObjectType.getFieldDefinition(endField).type).name
                "$childVariable${endField.capitalize()}:$label"
            } else "$childVariable:$innerType"
            val relPattern = if (relFromType) "$childVariable:${relType}" else ":${relType}"
            val where = where(childVariable, fieldDefinition, propertyArguments(field))
            val fieldProjection = projectFields(childVariable, field, fieldObjectType, ctx)

            val comprehension = "[($variable)$inArrow-[$relPattern]-$outArrow($endNodePattern)${where.query} | ${fieldProjection.query}]"
            val skipLimit = skipLimit(field.arguments)
            val slice = slice(skipLimit, fieldType.isList())
            return Cypher(comprehension + slice, (where.params + fieldProjection.params))
        } else {
            val relDirective = parent.definition.directivesByName.getValue("relation")
            val (relType, outgoing, endField) = relDetails(relDirective)

            val fieldProjection = projectFields(variable+endField.capitalize(), field, fieldObjectType, ctx)

            return Cypher(fieldProjection.query)
        }
    }

    private fun arrows(outgoing: Boolean?): Pair<String, String> {
        return when (outgoing) {
            false -> "<" to ""
            true -> "" to ">"
            null -> "" to ""
        }
    }

    private fun Directive.argumentString(name:String) : String {
        return this.getArgument(name)?.value?.toJavaValue()?.toString()
                ?: schema.getDirective(this.name).getArgument(name)?.defaultValue?.toString()
        ?: throw IllegalStateException("No default value for ${this.name}.${name}")
    }

    private fun relDetails(relDirective: Directive): Triple<String,Boolean?,String> {
        val relType = relDirective.argumentString("name")
        val outgoing =  when (relDirective.argumentString("direction")) {
            "IN" -> false
            "BOTH" -> null
            "OUT" -> true
            else -> throw IllegalStateException("Unknown direction ${relDirective.argumentString("direction")}")
        }
        val endField = if (outgoing == true) relDirective.argumentString("to")
        else relDirective.argumentString("from")
        return Triple(relType, outgoing, endField)
    }

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

    private fun GraphQLType.isList() = this is GraphQLList || (this is GraphQLNonNull && this.wrappedType is GraphQLList)

    private fun Field.aliasOrName() = (this.alias ?: this.name).quote()

    private fun String.quote() = this // do we actually need this? if (isJavaIdentifier()) this else '`'+this+'`'

    private fun String.isJavaIdentifier() =
            this[0].isJavaIdentifierStart() &&
                    this.substring(1).all { it.isJavaIdentifierPart() }

    private fun toCypherString(value:Any, type: GraphQLType ): String = when (value) {
        is String -> "'"+value+"'"
        else -> value.toString()
    }
    private fun Value.toCypherString(): String = when (this) {
        is StringValue -> "'"+this.value+"'"
        is EnumValue -> "'"+this.name+"'"
        is NullValue -> "null"
        is BooleanValue -> this.isValue.toString()
        is FloatValue -> this.value.toString()
        is IntValue -> this.value.toString()
        is VariableReference -> "$"+this.name
        is ArrayValue -> this.values.map { it.toCypherString() }.joinToString(",","[","]")
        else -> throw IllegalStateException("Unhandled value "+this)
    }

    private fun Value.toJavaValue(): Any? = when (this) {
        is StringValue -> this.value
        is EnumValue -> this.name
        is NullValue -> null
        is BooleanValue -> this.isValue
        is FloatValue -> this.value
        is IntValue -> this.value
        is VariableReference -> this
        is ArrayValue -> this.values.map { it.toJavaValue() }.toList()
        else -> throw IllegalStateException("Unhandled value "+this)
    }

    private fun inner(t: GraphQLType) : GraphQLType = when(t) {
         is GraphQLList -> inner(t.wrappedType)
         is GraphQLNonNull -> inner(t.wrappedType)
         else -> t
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
    fun buildSchema(sdl: String) : GraphQLSchema {
        val schemaParser = SchemaParser()
        val typeDefinitionRegistry = schemaParser.parse(sdl)

        val runtimeWiring = RuntimeWiring.newRuntimeWiring()
                .type("Query")
                { it.dataFetcher("hello") { env -> "Hello ${env.getArgument<Any>("what")}!" } }
                .build()

        val schemaGenerator = SchemaGenerator()
        val directives = setOf(GraphQLDirective("relation", "relation directive",
                EnumSet.of(Introspection.DirectiveLocation.FIELD,Introspection.DirectiveLocation.OBJECT),
                listOf(GraphQLArgument("name",Scalars.GraphQLString),
                        GraphQLArgument("direction","relationship direction",Scalars.GraphQLString,"OUT"),
                        GraphQLArgument("from","from field name",Scalars.GraphQLString,"from"),
                        GraphQLArgument("to","to field name",Scalars.GraphQLString,"to")),false,false,true))
        return schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring).transform { bc -> bc.additionalDirectives(directives).build() }
    }
}