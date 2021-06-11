package org.neo4j.graphql.utils

import graphql.language.InterfaceTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.diff.DiffSet
import graphql.schema.diff.SchemaDiff
import graphql.schema.diff.reporting.CapturingReporter
import graphql.schema.idl.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.graphql.*
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
                reg
                    .scalars()
                    .filterNot { entry -> ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.containsKey(entry.key) }
                    .forEach { (name, definition) ->
                        runtimeWiring.scalar(GraphQLScalarType.newScalar()
                            .name(name)
                            .definition(definition)
                            .coercing(NoOpCoercing)
                            .build()
                        )
                    }
                expectedSchema = schemaGenerator.makeExecutableSchema(reg, runtimeWiring.build())

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

    companion object {
        private const val GRAPHQL_MARKER = "[source,graphql]"
        private val METHOD_PATTERN = Pattern.compile("(add|delete|update|merge|create)(.*)")

        private val SCHEMA_PRINTER = SchemaPrinter(SchemaPrinter.Options.defaultOptions()
            .includeDirectives(false)
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
