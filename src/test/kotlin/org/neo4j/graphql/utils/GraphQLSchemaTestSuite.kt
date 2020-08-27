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
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.graphql.DynamicProperties
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.SchemaConfig
import org.opentest4j.AssertionFailedError
import java.util.*
import java.util.regex.Pattern
import java.util.stream.Stream

class GraphQLSchemaTestSuite(fileName: String) : AsciiDocTestSuite(fileName) {

    fun run(): Stream<DynamicNode> {
        return parse(linkedSetOf("[source,json]", "[source,graphql]"))
    }

    override fun testFactory(title: String, schema: String, codeBlocks: Map<String, ParsedBlock>, ignore: Boolean): List<DynamicNode> {
        val targetSchemaBlock = codeBlocks["[source,graphql]"]
        val compareSchemaTest = DynamicTest.dynamicTest("compare schema", targetSchemaBlock?.uri, {
            val configBlock = codeBlocks["[source,json]"]
            val config = MAPPER.readValue(configBlock?.code?.toString()
                    ?: throw IllegalStateException("missing config $title"), SchemaConfig::class.java)

            val targetSchema = targetSchemaBlock?.code?.trim()?.toString()
                    ?: throw IllegalStateException("missing graphql for $title")

            var augmentedSchema: GraphQLSchema? = null
            var expectedSchema: GraphQLSchema? = null
            try {
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
                    throw AssertionFailedError("augmented schema differs for '$title'",
                            expectedSchema?.let { SCHEMA_PRINTER.print(it) } ?: targetSchema,
                            SCHEMA_PRINTER.print(augmentedSchema),
                            e)

                }
            }
        })
        return Collections.singletonList(compareSchemaTest)
    }

    companion object {
        private val METHOD_PATTERN = Pattern.compile("(add|delete|update|merge|create)(.*)")

        private val SCHEMA_PRINTER = SchemaPrinter(SchemaPrinter.Options.defaultOptions()
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

        fun GraphQLType.splitName(): Pair<String?, String> {
            val m = METHOD_PATTERN.matcher(this.name)
            return if (m.find()) {
                m.group(1) to m.group(2).toLowerCase()
            } else {
                null to this.name.toLowerCase()
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
