package org.neo4j.graphql.factories

import graphql.schema.GraphQLSchema
import graphql.schema.diff.SchemaDiff
import graphql.schema.diff.SchemaDiffSet
import graphql.schema.diff.reporting.CapturingReporter
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.asn1.x500.style.RFC4519Style.title
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.asciidoc.ast.Section
import org.neo4j.graphql.domain.TestCase
import org.neo4j.graphql.utils.JsonUtils
import org.neo4j.graphql.utils.SchemaUtils
import org.neo4j.graphql.utils.TestUtils.TEST_RESOURCES
import org.opentest4j.AssertionFailedError
import java.nio.file.Path
import java.util.*

class GraphQLSchemaTestFactory(file: Path) : AsciiDocTestFactory(file) {

    constructor(file: String) : this(Path.of(TEST_RESOURCES, file))

    override fun createTests(testCase: TestCase, section: Section, ignoreReason: String?): List<DynamicNode> {
        if (testCase.setup.schema == null) {
            return emptyList()
        }
        val targetSchemaBlock = testCase.augmentedSchema
        targetSchemaBlock?.let {
            try {
                it.reformattedContent = SchemaUtils.prettyPrintSchema(it.content)
            } catch (ignore: Exception) {
            }
        }
        if (targetSchemaBlock == null) {
            return emptyList()
        }
        val compareSchemaTest = DynamicTest.dynamicTest("compare schema", targetSchemaBlock.uri) {
            val configBlock = testCase.setup.schemaConfig
            val config = configBlock?.content?.let { JsonUtils.parseJson<SchemaConfig>(it) } ?: SchemaConfig()

            val targetSchema = targetSchemaBlock.content

            var augmentedSchema: GraphQLSchema? = null
            var expectedSchema: GraphQLSchema? = null
            try {
                val schema = testCase.setup.schema.content
                augmentedSchema = SchemaBuilder.buildSchema(schema, config, addLibraryDirectivesToSchema = false)
                expectedSchema = SchemaUtils.createMockSchema(targetSchema)

                diff(expectedSchema, augmentedSchema)
                diff(augmentedSchema, expectedSchema)
                targetSchemaBlock.generatedContent = SchemaUtils.prettyPrintSchema(augmentedSchema)
            } catch (e: Throwable) {
                if (ignoreReason != null) {
                    Assumptions.assumeFalse(true) { "$ignoreReason ${e.message}" }
                } else {
                    if (augmentedSchema == null) {
                        Assertions.fail<Throwable>(e)
                    }
                    val actualSchema = SchemaUtils.prettyPrintSchema(augmentedSchema)
                    targetSchemaBlock.generatedContent = actualSchema
                    throw AssertionFailedError(
                        "augmented schema differs for '$title'",
                        expectedSchema?.let { SchemaUtils.prettyPrintSchema(it) } ?: targetSchema,
                        actualSchema,
                        e)

                }
            }
        }
        return Collections.singletonList(compareSchemaTest)
    }

    private fun diff(augmentedSchema: GraphQLSchema, expected: GraphQLSchema) {
        val diffSet = SchemaDiffSet.diffSetFromIntrospection(augmentedSchema, expected)
        val capture = CapturingReporter()
        SchemaDiff().diffSchema(diffSet, capture)
        assertThat(capture.dangers).isEmpty()
        assertThat(capture.breakages).isEmpty()
    }
}
