package org.neo4j.graphql.utils

import graphql.language.InterfaceTypeDefinition
import graphql.language.UnionTypeDefinition
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.idl.*
import org.neo4j.graphql.NoOpCoercing

object SchemaUtils {

    private val SCHEMA_PRINTER = SchemaPrinter(
        SchemaPrinter.Options.defaultOptions()
            .includeScalarTypes(true)
            .includeSchemaDefinition(true)
            .includeIntrospectionTypes(false)
            .includeDirectiveDefinition {
                // skip printing of graphql native directives
                setOf("deprecated", "include", "oneOf", "skip", "specifiedBy").contains(it).not()
            }
    )

    fun prettyPrintSchema(schema: String): String = SCHEMA_PRINTER.print(createMockSchema(schema))

    fun prettyPrintSchema(schema: GraphQLSchema?): String = SCHEMA_PRINTER.print(schema)

    fun createMockSchema(schema: String): GraphQLSchema {
        val schemaParser = SchemaParser()

        val reg = schemaParser.parse(schema)
        val schemaGenerator = SchemaGenerator()
        val runtimeWiring = RuntimeWiring.newRuntimeWiring()
        (reg.getTypes(InterfaceTypeDefinition::class.java)
                + reg.getTypes(UnionTypeDefinition::class.java))
            .forEach { typeDefinition -> runtimeWiring.type(typeDefinition.name) { it.typeResolver { null } } }
        reg
            .scalars()
            .filterNot { entry -> ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.containsKey(entry.key) }
            .forEach { (name, definition) ->
                runtimeWiring.scalar(
                    GraphQLScalarType.newScalar()
                        .name(name)
                        .definition(definition)
                        .coercing(NoOpCoercing)
                        .build()
                )
            }
        return schemaGenerator.makeExecutableSchema(reg, runtimeWiring.build())
    }

}
