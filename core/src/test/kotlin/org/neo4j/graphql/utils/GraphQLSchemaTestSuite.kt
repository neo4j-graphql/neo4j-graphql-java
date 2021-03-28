package org.neo4j.graphql.utils

import graphql.language.InterfaceTypeDefinition
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.diff.DiffSet
import graphql.schema.diff.SchemaDiff
import graphql.schema.diff.reporting.CapturingReporter
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.graphql.DynamicProperties
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.requiredName
import org.opentest4j.AssertionFailedError
import java.util.*
import java.util.regex.Pattern

class GraphQLSchemaTestSuite(fileName: String) : AsciiDocTestSuite(
        fileName,
        listOf(SCHEMA_CONFIG_MARKER, GRAPHQL_MARKER)
) {

    override fun testFactory(title: String, globalBlocks: Map<String, ParsedBlock>, codeBlocks: Map<String, ParsedBlock>, ignore: Boolean): List<DynamicNode> {
        val targetSchemaBlock = codeBlocks[GRAPHQL_MARKER]
        val compareSchemaTest = DynamicTest.dynamicTest("compare schema", targetSchemaBlock?.uri) {
            val configBlock = codeBlocks[SCHEMA_CONFIG_MARKER]
            val config = configBlock?.code()?.let { MAPPER.readValue(it, SchemaConfig::class.java) } ?: SchemaConfig()

            val targetSchema = targetSchemaBlock?.code()
                    ?: throw IllegalStateException("missing graphql for $title")

            var augmentedSchema: GraphQLSchema? = null
            var expectedSchema: GraphQLSchema? = null
            try {
                val schema = globalBlocks[SCHEMA_MARKER]?.code()
                        ?: throw IllegalStateException("Schema should be defined")
                augmentedSchema = SchemaBuilder.buildSchema(schema, config)
                val schemaParser = SchemaParser()

                val reg = schemaParser.parse(targetSchema)
                val schemaGenerator = SchemaGenerator()
                val runtimeWiring = RuntimeWiring.newRuntimeWiring()
                reg
                    .getTypes(InterfaceTypeDefinition::class.java)
                    .forEach { typeDefinition -> runtimeWiring.type(typeDefinition.name) { it.typeResolver { null } } }
                expectedSchema = schemaGenerator.makeExecutableSchema(reg, runtimeWiring
                    .scalar(DynamicProperties.INSTANCE)
                    .build())

                diff(expectedSchema, augmentedSchema)
                diff(augmentedSchema, expectedSchema)
            } catch (e: Throwable) {
                if (ignore) {
                    Assumptions.assumeFalse(true, e.message)
                } else {
                    val actualSchema = SCHEMA_PRINTER.print(augmentedSchema)
                    targetSchemaBlock.adjustedCode = actualSchema + "\n" +
                            // this is added since the SCHEMA_PRINTER is not able to print global directives
                            javaClass.getResource("/lib_directives.graphql").readText()
                    throw AssertionFailedError("augmented schema differs for '$title'",
                            expectedSchema?.let { SCHEMA_PRINTER.print(it) } ?: targetSchema,
                            actualSchema,
                            e)

                }
            }
        }
        return Collections.singletonList(compareSchemaTest)
    }

    companion object {
        private const val GRAPHQL_MARKER = "[source,graphql]"
        private val METHOD_PATTERN = Pattern.compile("(add|delete|update|merge|create)(.*)")

        private val SCHEMA_PRINTER = SchemaPrinter(SchemaPrinter.Options.defaultOptions()
            .includeDirectives(true)
            .includeScalarTypes(true)
            .includeSchemaDefinition(true)
            .includeIntrospectionTypes(false)
        )

        fun GraphQLType.splitName(): Pair<String?, String> {
            val m = METHOD_PATTERN.matcher(this.requiredName())
            return if (m.find()) {
                m.group(1) to m.group(2).toLowerCase()
            } else {
                null to this.requiredName().toLowerCase()
            }
        }

        fun diff(augmentedSchema: GraphQLSchema, expected: GraphQLSchema) {
            val diffSet = DiffSet.diffSet(augmentedSchema, expected)
            val capture = CapturingReporter()
            SchemaDiff(SchemaDiff.Options.defaultOptions().enforceDirectives())
                .diffSchema(diffSet, capture)
            assertThat(capture.dangers).isEmpty()
            assertThat(capture.breakages).isEmpty()
        }
    }
}
