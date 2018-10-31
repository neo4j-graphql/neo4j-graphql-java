package org.neo4j.graphql

import graphql.Scalars
import graphql.language.*
import graphql.parser.Parser
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.antlr.v4.runtime.misc.ParseCancellationException

class Translator(val schema: GraphQLSchema) {
    data class Config( val topLevelWhere: Boolean = true)

    fun translate(query: String, params: Map<String, Any> = emptyMap(), config: Config = Config()) : Pair<List<String>,Map<String,Any>> {
        val ast = parse(query) // todo preparsedDocumentProvider
        val queries = ast.definitions.filterIsInstance<OperationDefinition>()
                .filter { it.operation == OperationDefinition.Operation.QUERY } // todo variabledefinitions, directives, name
                .flatMap { it.selectionSet.selections }
                .filterIsInstance<Field>() // FragmentSpread, InlineFragment
                .map { println(it);it }
                .map { toQuery(it, config) } // arguments, alias, directives, selectionSet
        return queries to params
    }

    private fun toQuery(queryField: Field, config:Config = Config()): String {
        val name = queryField.name
        val queryType = schema.queryType.fieldDefinitions.filter { it.name == name }.firstOrNull() ?: throw IllegalArgumentException("Unknown Query $name available queries: " + schema.queryType.fieldDefinitions.map { it.name }.joinToString())
        val returnType = inner(queryType.type)
//        println(returnType)
        val type = schema.getType(returnType.name)
        val label = type.name.quote()
        val variable = queryField.aliasOrName().decapitalize()
        val mapProjection = projectFields(variable, queryField, type)
        val where = if (config.topLevelWhere) where(variable, queryType, queryField.arguments) else ""
        val properties = if (config.topLevelWhere) "" else properties(variable, queryType, queryField.arguments)
        val skipLimit = format(skipLimit(queryField.arguments))
        return "MATCH ($variable:$label$properties)$where RETURN $mapProjection AS $variable$skipLimit"
    }

    private fun where(variable: String, field: GraphQLFieldDefinition, arguments: MutableList<Argument>) : String {
        val whereArgs = arguments.filterNot { it.name.toLowerCase() in listOf("first", "offset") }
        if (whereArgs.isEmpty()) return ""
        val predicates = whereArgs.map { it.name to it.value.toCypherString() }.toMap()
        val defaults = field.arguments.filter { it.defaultValue  != null && !predicates.containsKey(it.name) }.map { it.name to toCypherString(it.defaultValue, it.type) }
        return " WHERE "+(predicates + defaults).map { (k,v) -> "$variable.$k = $v"}.joinToString(" AND ")
    }

    private fun properties(variable: String, field: GraphQLFieldDefinition, arguments: MutableList<Argument>) : String {
        val whereArgs = arguments.filterNot { it.name.toLowerCase() in listOf("first", "offset") }
        if (whereArgs.isEmpty()) return ""
        val predicates = whereArgs.map { it.name to it.value.toCypherString() }.toMap()
        val defaults = field.arguments.filter { it.defaultValue  != null && !predicates.containsKey(it.name) }.map { it.name to toCypherString(it.defaultValue, it.type) }
        return(predicates + defaults).map { (k,v) -> "$k:$v"}.joinToString(" , "," {","}")
    }

    private fun projectFields(variable: String, field: Field, type: GraphQLType): String {
        // todo handle non-object case
        val objectType = type as GraphQLObjectType
        val properties = field.selectionSet.selections
                .filterIsInstance<Field>()
                .map { resolveField(variable, it, objectType) }
                .joinToString(",", "{ ", " }")
        val mapProjection = "$variable $properties"
        return mapProjection
    }

    private fun resolveField(variable: String, field: Field, type: GraphQLObjectType) : String {
        val fieldDefinition = type.getFieldDefinition(field.name)
        return if (inner(fieldDefinition.type) is GraphQLObjectType) {
            val patternComprehensions = projectRelationship(variable, field, fieldDefinition)
            field.aliasOrName() + ":" + patternComprehensions
        } else "." + field.aliasOrName()
    }

    private fun projectRelationship(variable: String, field: Field, fieldDefinition: GraphQLFieldDefinition): String {
        val fieldType = fieldDefinition.type
        val innerType = inner(fieldType).name
        val fieldObjectType = schema.getType(innerType)
        val relDirective = fieldDefinition.definition.getDirective("relation")
                ?: throw IllegalStateException("Field $field needs an @relation directive")
        val relType = relDirective.getArgument("name").value.toJavaValue()
        val relDirection = relDirective.getArgument("direction").value.toJavaValue()
        val (inArrow, outArrow) = if (relDirection.toString() == "IN") "<" to "" else "" to ">"
        val childVariable = field.name + fieldObjectType.name
        val childPattern = "$childVariable:$innerType"
        val where = where(childVariable,fieldDefinition,field.arguments)
        val comprehension = "[($variable)$inArrow-[:${relType}]-$outArrow($childPattern)$where | ${projectFields(childVariable, field, fieldObjectType)}]"
        val skipLimit = skipLimit(field.arguments)
        val slice = slice(skipLimit,fieldType.isList())
        return comprehension + slice
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
        is VariableReference -> "$"+this.name
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
        val directives = setOf(GraphQLDirective.newDirective().name("relation").argument { it.name("name").type(Scalars.GraphQLString).also { it.name("direction").type(Scalars.GraphQLString) } }.build())
        return schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring).transform { bc -> bc.additionalDirectives(directives).build() }
    }
}