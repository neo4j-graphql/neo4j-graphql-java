package org.neo4j.graphql

import graphql.language.*
import graphql.parser.Parser
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.antlr.v4.runtime.misc.ParseCancellationException

class Translator(val schema: GraphQLSchema) {
    fun translate(query: String, params: Map<String,Any> = emptyMap()) : Pair<List<String>,Map<String,Any>> {
        val ast = parse(query) // todo preparsedDocumentProvider
        val queries = ast.definitions.filterIsInstance<OperationDefinition>()
                .filter { it.operation == OperationDefinition.Operation.QUERY } // todo variabledefinitions, directives, name
                .flatMap { it.selectionSet.selections }
                .filterIsInstance<Field>() // FragmentSpread, InlineFragment
                .map { println(it);it }
                .map { toQuery(it) } // arguments, alias, directives, selectionSet
        return queries to params
    }

    private fun toQuery(queryField: Field): String {
        val name = queryField.name
        val queryType = schema.queryType.fieldDefinitions.filter { it.name == name }.firstOrNull() ?: throw IllegalArgumentException("Unknown Query $name available queries: " + schema.queryType.fieldDefinitions.map { it.name }.joinToString())
        val returnType = inner(queryType.type)
//        println(returnType)
        val type = schema.getType(returnType.name)
        val label = type.name.quote()
        val variable = nameOrAlias(queryField)
        val mapProjection = projectFields(variable, queryField)
        val where = where(variable, queryType, queryField.arguments)
        return "MATCH ($variable:$label)$where RETURN $mapProjection"
    }

    private fun where(variable: String, field: GraphQLFieldDefinition, arguments: MutableList<Argument>) : String {
        if (arguments.isEmpty()) return ""
        val predicates = arguments.map { it.name to it.value.toCypherString() }.toMap()
        val defaults = field.arguments.filter { it.defaultValue  != null && !predicates.containsKey(it.name) }.map { it.name to toCypherString(it.defaultValue, it.type) }
        return " WHERE "+(predicates + defaults).map { (k,v) -> "$variable.$k = $v"}.joinToString(" AND ")
    }

    private fun projectFields(variable: String, field: Field): String {
        val properties = field.selectionSet.selections
                .filterIsInstance<Field>()
                .map { "." + nameOrAlias(it) }
                .joinToString(",", "{", "}")
        val mapProjection = "$variable $properties"
        return mapProjection
    }

    private fun nameOrAlias(it: Field) = (it.alias ?: it.name).quote()

    private inline fun String.quote() = this // do we actually need this? if (isJavaIdentifier()) this else '`'+this+'`'

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
        return schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
    }
}