package org.neo4j.graphql.utils

import graphql.language.InterfaceTypeDefinition
import graphql.language.UnionTypeDefinition
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.diff.SchemaDiff
import graphql.schema.diff.SchemaDiffSet
import graphql.schema.diff.reporting.CapturingReporter
import graphql.schema.idl.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.graphql.NoOpCoercing
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.SchemaConfig
import org.opentest4j.AssertionFailedError
import java.util.*

class GraphQLSchemaTestSuite(fileName: String) : AsciiDocTestSuite(fileName, TEST_CASE_MARKERS) {

    override fun testFactory(
        title: String,
        globalBlocks: Map<String, List<ParsedBlock>>,
        codeBlocks: Map<String, List<ParsedBlock>>,
        ignore: Boolean
    ): List<DynamicNode> {
        val targetSchemaBlock = codeBlocks[GRAPHQL_MARKER]?.first()
        targetSchemaBlock?.let {
            try {
                it.reformattedCode = SCHEMA_PRINTER.print(createMockSchema(it.code()))
            } catch (ignore: Exception) {
            }
        }
        val compareSchemaTest = DynamicTest.dynamicTest("compare schema", targetSchemaBlock?.uri) {
            val configBlock = codeBlocks[SCHEMA_CONFIG_MARKER]?.first()
            val config = configBlock?.code()?.let { MAPPER.readValue(it, SchemaConfig::class.java) } ?: SchemaConfig()

            val targetSchema = targetSchemaBlock?.code()
                ?: throw IllegalStateException("missing graphql for $title")

            var augmentedSchema: GraphQLSchema? = null
            var expectedSchema: GraphQLSchema? = null
            try {
                val schema = globalBlocks[SCHEMA_MARKER]?.first()?.code()
                    ?: throw IllegalStateException("Schema should be defined")
                augmentedSchema = SchemaBuilder.buildSchema(schema, config)
                expectedSchema = createMockSchema(targetSchema)

                diff(expectedSchema, augmentedSchema)
                diff(augmentedSchema, expectedSchema)
                targetSchemaBlock.adjustedCode = SCHEMA_PRINTER.print(augmentedSchema)
            } catch (e: Throwable) {
                if (ignore) {
                    Assumptions.assumeFalse(true, e.message)
                } else {
                    if (augmentedSchema == null) {
                        Assertions.fail<Throwable>(e)
                    }
                    val actualSchema = SCHEMA_PRINTER.print(augmentedSchema)
                    targetSchemaBlock.adjustedCode = actualSchema
                    throw AssertionFailedError("augmented schema differs for '$title'",
                        expectedSchema?.let { SCHEMA_PRINTER.print(it) } ?: targetSchema,
                        actualSchema,
                        e)

                }
            }
        }
        return Collections.singletonList(compareSchemaTest)
    }

    private fun createMockSchema(targetSchema: String): GraphQLSchema {
        val schemaParser = SchemaParser()

        val reg = schemaParser.parse(targetSchema)
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

    companion object {
        private const val GRAPHQL_MARKER = "[source,graphql]"
        private val TEST_CASE_MARKERS: List<String> = listOf(SCHEMA_CONFIG_MARKER, GRAPHQL_MARKER)

        private val SCHEMA_PRINTER = SchemaPrinter(
            SchemaPrinter.Options.defaultOptions()
                .includeDirectives(false)
                .includeScalarTypes(true)
                .includeSchemaDefinition(true)
                .includeIntrospectionTypes(false)
        )

        fun diff(augmentedSchema: GraphQLSchema, expected: GraphQLSchema) {
            val diffSet = SchemaDiffSet.diffSetFromIntrospection(augmentedSchema, expected)
            val capture = CapturingReporter()
            SchemaDiff().diffSchema(diffSet, capture)
            assertThat(capture.dangers).isEmpty()
            assertThat(capture.breakages).isEmpty()
        }
    }
}
