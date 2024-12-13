package org.neo4j.graphql.utils

import demo.org.neo4j.graphql.utils.asciidoc.ast.CodeBlock
import demo.org.neo4j.graphql.utils.asciidoc.ast.Section
import graphql.language.InterfaceTypeDefinition
import graphql.language.UnionTypeDefinition
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.diff.SchemaDiff
import graphql.schema.diff.SchemaDiffSet
import graphql.schema.diff.reporting.CapturingReporter
import graphql.schema.idl.*
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.asn1.x500.style.RFC4519Style.title
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.graphql.NoOpCoercing
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.SchemaConfig
import org.opentest4j.AssertionFailedError
import java.util.*

class GraphQLSchemaTestSuite(fileName: String) : AsciiDocTestSuite<GraphQLSchemaTestSuite.TestCase>(
    fileName,
    listOf(
        matcher("graphql", exactly = true, setter = TestCase::augmentedSchema),
    )
) {

    data class TestCase(
        var schema: CodeBlock,
        var schemaConfig: CodeBlock?,
        var augmentedSchema: CodeBlock? = null,
    )

    override fun createTestCase(section: Section): TestCase? {
        val schema = findSetupCodeBlocks(section, "graphql", mapOf("schema" to "true")).firstOrNull() ?: return null
        val schemaConfig = findSetupCodeBlocks(section, "json", mapOf("schema-config" to "true")).firstOrNull()
        return TestCase(schema, schemaConfig)
    }

    override fun createTests(testCase: TestCase, section: Section, ignoreReason: String?): List<DynamicNode> {
        val targetSchemaBlock = testCase.augmentedSchema
        targetSchemaBlock?.let {
            try {
                it.reformattedContent = SCHEMA_PRINTER.print(createMockSchema(it.content))
            } catch (ignore: Exception) {
            }
        }
        if (targetSchemaBlock == null) {
            return emptyList()
        }
        val compareSchemaTest = DynamicTest.dynamicTest("compare schema", targetSchemaBlock.uri) {
            val configBlock = testCase.schemaConfig
            val config = configBlock?.content?.let { MAPPER.readValue(it, SchemaConfig::class.java) } ?: SchemaConfig()

            val targetSchema = targetSchemaBlock.content

            var augmentedSchema: GraphQLSchema? = null
            var expectedSchema: GraphQLSchema? = null
            try {
                val schema = testCase.schema.content
                augmentedSchema = SchemaBuilder.buildSchema(schema, config, addLibraryDirectivesToSchema = false)
                expectedSchema = createMockSchema(targetSchema)

                diff(expectedSchema, augmentedSchema)
                diff(augmentedSchema, expectedSchema)
                targetSchemaBlock.generatedContent = SCHEMA_PRINTER.print(augmentedSchema)
            } catch (e: Throwable) {
                if (ignoreReason != null) {
                    Assumptions.assumeFalse(true) { "$ignoreReason ${e.message}" }
                } else {
                    if (augmentedSchema == null) {
                        Assertions.fail<Throwable>(e)
                    }
                    val actualSchema = SCHEMA_PRINTER.print(augmentedSchema)
                    targetSchemaBlock.generatedContent = actualSchema
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

        fun diff(augmentedSchema: GraphQLSchema, expected: GraphQLSchema) {
            val diffSet = SchemaDiffSet.diffSetFromIntrospection(augmentedSchema, expected)
            val capture = CapturingReporter()
            SchemaDiff().diffSchema(diffSet, capture)
            assertThat(capture.dangers).isEmpty()
            assertThat(capture.breakages).isEmpty()
        }
    }
}
