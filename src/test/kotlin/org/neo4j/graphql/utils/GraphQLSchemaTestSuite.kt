package org.neo4j.graphql.utils

import graphql.language.InterfaceTypeDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.diff.DiffSet
import graphql.schema.diff.SchemaDiff
import graphql.schema.diff.reporting.CapturingReporter
import graphql.schema.idl.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.neo4j.graphql.DynamicProperties
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.SchemaConfig
import org.opentest4j.AssertionFailedError
import java.io.File
import java.util.regex.Pattern
import javax.ws.rs.core.UriBuilder

class GraphQLSchemaTestSuite(fileName: String) : AsciiDocTestSuite() {
    val schema: String
    val schemaPrinter = SchemaPrinter(SchemaPrinter.Options.defaultOptions()
        .includeDirectives(true)
        .includeScalarTypes(true)
        .includeExtendedScalarTypes(true)
        .includeSchemaDefintion(true)
        .includeIntrospectionTypes(false)
        .setComparators(DefaultSchemaPrinterComparatorRegistry.newComparators()
            .addComparator({ env ->
                env.parentType(GraphQLObjectType::class.java)
                env.elementType(GraphQLFieldDefinition::class.java)
            }, GraphQLFieldDefinition::class.java, fun(o1: GraphQLFieldDefinition, o2: GraphQLFieldDefinition): Int {
                val (op1, name1) = o1.splitName()
                val (op2, name2) = o2.splitName()
                if (op1 == null && op2 == null) {
                    return name1.compareTo(name2)
                }
                if (op1 == null) {
                    return -1
                }
                if (op2 == null) {
                    return 1
                }
                val prio1 = name1.compareTo(name2)
                if (prio1 == 0) {
                    return op1.compareTo(op2)
                }
                return prio1
            })
            .build())
    )

    class TestRun(
            private val suite: GraphQLSchemaTestSuite,
            val title: String?,
            var file: File,
            val line: Int,
            private val config: SchemaConfig,
            private val targetSchema: String,
            private val ignore: Boolean) {

        fun run() {
            var augmentedSchema: GraphQLSchema? = null
            var expectedSchema: GraphQLSchema? = null
            try {
                augmentedSchema = SchemaBuilder.buildSchema(suite.schema, config)
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
                    throw AssertionFailedError("augmented schema differs for '$title'",
                            expectedSchema?.let { suite.schemaPrinter.print(it) } ?: targetSchema,
                            suite.schemaPrinter.print(augmentedSchema),
                            e)

                }
            }
        }

        private fun diff(augmentedSchema: GraphQLSchema, expected: GraphQLSchema) {
            val diffSet = DiffSet.diffSet(augmentedSchema, expected)
            val capture = CapturingReporter()
            SchemaDiff(SchemaDiff.Options.defaultOptions().enforceDirectives())
                .diffSchema(diffSet, capture)
            assertThat(capture.dangers).isEmpty()
            assertThat(capture.breakages).isEmpty()
        }
    }

    val tests: List<TestRun>

    init {
        val result = parse(fileName, linkedSetOf("[source,json]", "[source,graphql]"))
        schema = result.schema
        tests = result.tests.map {
            TestRun(this,
                    it.title,
                    result.file,
                    it.line,
                    MAPPER.readValue(it.codeBlocks["[source,json]"]?.toString()
                            ?: throw IllegalStateException("missing config ${it.title}"), SchemaConfig::class.java),
                    it.codeBlocks["[source,graphql]"]?.trim()?.toString()
                            ?: throw IllegalStateException("missing graphql for ${it.title}"),
                    it.ignore
            )
        }
    }

    fun run(): List<DynamicTest> {
        return tests.map {
            DynamicTest.dynamicTest(
                    it.title,
                    UriBuilder.fromUri(it.file.toURI()).queryParam("line", it.line).build()) { it.run() }
        }
    }

    companion object {
        private val METHOD_PATTERN = Pattern.compile("(add|delete|update|merge|create)(.*)")
        fun GraphQLType.splitName(): Pair<String?, String> {
            val m = METHOD_PATTERN.matcher(this.name)
            return if (m.find()) {
                m.group(1) to m.group(2).toLowerCase()
            } else {
                null to this.name.toLowerCase()
            }
        }
    }
}
