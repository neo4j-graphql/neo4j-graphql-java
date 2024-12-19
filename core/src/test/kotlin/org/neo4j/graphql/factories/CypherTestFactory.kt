package org.neo4j.graphql.factories

import graphql.ExceptionWhileDataFetching
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.GraphQLError
import graphql.execution.NonNullableFieldWasNullError
import graphql.schema.DataFetcher
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLSchema
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.driver.Driver
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.asciidoc.ast.CodeBlock
import org.neo4j.graphql.asciidoc.ast.Section
import org.neo4j.graphql.custom_resolver.TestDataFetcher
import org.neo4j.graphql.domain.TestCase
import org.neo4j.graphql.driver.adapter.Neo4jAdapter
import org.neo4j.graphql.driver.adapter.Neo4jDriverAdapter
import org.neo4j.graphql.utils.Assertions.assertCypherParams
import org.neo4j.graphql.utils.Assertions.assertEqualIgnoreOrder
import org.neo4j.graphql.utils.Assertions.assertWithJsonPath
import org.neo4j.graphql.utils.CypherUtils
import org.neo4j.graphql.utils.JsonUtils
import org.neo4j.graphql.utils.TestUtils.TEST_RESOURCES
import org.opentest4j.AssertionFailedError
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.concurrent.FutureTask
import kotlin.reflect.full.findAnnotation

class CypherTestFactory(file: Path, private val driver: Driver? = null, createMissingBlocks: Boolean = true) :
    AsciiDocTestFactory(file, createMissingBlocks) {

    constructor(file: String, driver: Driver?) : this(Path.of(TEST_RESOURCES, file), driver)

    data class CypherResult(val query: String, val params: Map<String, Any?>)

    override fun createTests(testCase: TestCase, section: Section, ignoreReason: String?): List<DynamicNode> {
        if (testCase.graphqlRequest == null || testCase.setup.schema == null) {
            return emptyList()
        }
        if (ignoreReason != null) {
            return listOf(DynamicTest.dynamicTest("Test Cypher", testCase.cypher?.uri) {
                Assumptions.assumeFalse(true) { ignoreReason }
            })
        }

        val result = createTransformationTask(testCase)

        val tests = mutableListOf<DynamicNode>()

        if (DEBUG) {
            tests.add(printGeneratedQuery(result))
            tests.add(printReplacedParameter(result))
        }
        if (driver != null) {
            val testData = testCase.setup.testData.firstOrNull()
            val responseAssertions = testCase.graphqlResponseAssertions
            var response = testCase.graphqlResponse
            if (responseAssertions == null && response == null) {
                response =
                    createCodeBlock(testCase.graphqlRequest!!, "json", "GraphQL-Response", mapOf("response" to "true"))
                testCase.graphqlResponse = response
            }
            if (testData != null && response != null || responseAssertions != null) {
                tests.add(integrationTest(section.title, testCase))
            }
        }

        testCypher(testCase.cypher, result)?.let { tests.add(it) }
        testCypherParams(testCase.cypher, testCase.cypherParams, result)?.let { tests.add(it) }
        return tests
    }

    private fun createSchema(
        setup: TestCase.Setup,
        neo4jAdapter: Neo4jAdapter = Neo4jAdapter.NO_OP
    ): GraphQLSchema {
        val schemaString = setup.schema?.content ?: error("missing schema")
        val schemaConfig = setup.schemaConfig?.content
            ?.let { return@let JsonUtils.parseJson<SchemaConfig>(it) }
            ?: SchemaConfig()
        val codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry()

        setup.customResolver?.let { registerTestResolver(it, codeRegistryBuilder) }

        return SchemaBuilder.fromSchema(schemaString, schemaConfig)
            .withNeo4jAdapter(neo4jAdapter)
            .withCodeRegistryBuilder(codeRegistryBuilder)
            .build()
    }

    private fun registerTestResolver(
        customResolver: CodeBlock,
        codeRegistryBuilder: GraphQLCodeRegistry.Builder
    ) {
        val path = customResolver.content.removePrefix("include::").replace("[]", "")
        if (path == customResolver.content) {
            error("Custom resolver must be an include statement")
        }
        val resolvedFile = file.toUri().resolve(path)
        val className = File(resolvedFile).toRelativeString(File("src/test/kotlin").absoluteFile)
            .replace(".kt", "")
            .replace("/", ".")

        val clazz = Class.forName(className).kotlin
        clazz.findAnnotation<TestDataFetcher>()?.let { annot ->
            codeRegistryBuilder.dataFetcher(
                FieldCoordinates.coordinates(annot.type, annot.field),
                clazz.objectInstance as DataFetcher<*>
            )
        }
    }

    private fun createTransformationTask(testCase: TestCase): () -> CypherResult {
        val transformationTask = FutureTask {

            val cypherResults = mutableListOf<CypherResult>()

            val schema = createSchema(testCase.setup, object : Neo4jAdapter {

                override fun getDialect(): Neo4jAdapter.Dialect = Neo4jAdapter.Dialect.NEO4J_5

                override fun executeQuery(
                    cypher: String,
                    params: Map<String, Any?>
                ): List<Map<String, Any?>> {
                    cypherResults.add(CypherResult(cypher, params))
                    return emptyList()
                }
            })

            val request = testCase.graphqlRequest!!.content

            val requestParams = testCase.graphqlRequestVariables?.content
                ?.let { JsonUtils.parseJson<Map<String, Any?>>(it) }
                ?: emptyMap()

            val queryContext = testCase.queryConfig?.content
                ?.let<String, QueryContext?> { config ->
                    return@let JsonUtils.parseJson<QueryContext>(config)
                }
                ?: QueryContext()

            val gql: GraphQL = GraphQL.newGraphQL(schema).build()
            val executionInput = ExecutionInput.newExecutionInput()
                .query(request)
                .variables(requestParams)
                .graphQLContext(mapOf(QueryContext.KEY to queryContext))
                .build()
            val result = gql.execute(executionInput)
            result.errors?.forEach {
                when (it) {
                    is NonNullableFieldWasNullError, // expected since we always return en empty list
                        -> {
                        // ignore
                    }
                    // generic error handling
                    is GraphQLError -> throw InvalidQueryException(it)
                }
            }
            cypherResults.single()
        }
        return {
            transformationTask.run()
            transformationTask.get()
        }
    }

    private fun printGeneratedQuery(result: () -> CypherResult): DynamicTest =
        DynamicTest.dynamicTest("Generated query") { println(result()) }

    private fun printReplacedParameter(result: () -> CypherResult): DynamicTest =
        DynamicTest.dynamicTest("Generated query with params replaced") {
            result().also {
                var queryWithReplacedParams = it.query
                it.params.forEach { (key, value) ->
                    queryWithReplacedParams =
                        queryWithReplacedParams.replace(
                            "$$key",
                            if (value is String) "'$value'" else value.toString()
                        )
                }
                println()
                println("Generated query with params replaced")
                println("------------------------------------")
                println(queryWithReplacedParams)
            }
        }

    private fun testCypher(cypherBlock: CodeBlock?, result: () -> CypherResult): DynamicTest? {
        if (cypherBlock == null) {
            return null
        }
        return DynamicTest.dynamicTest("Test Cypher", cypherBlock.uri) {
            val cypher = cypherBlock.content
            val expectedNormalized = if (cypher.isNotBlank()) CypherUtils.normalizeCypher(cypher) else ""
            val actual = result().query
            val actualNormalized = CypherUtils.normalizeCypher(actual)

            if (!Objects.equals(expectedNormalized, actual)) {
                cypherBlock.generatedContent = actual
            }
            if (actualNormalized != expectedNormalized) {
                val splitter =
                    "\n\n~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n~  source query\n~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n\n"
                throw AssertionFailedError(
                    "Cypher does not match",
                    expectedNormalized + splitter + cypher,
                    actualNormalized + splitter + actual
                )
                // TODO
                //  throw AssertionFailedError("Cypher does not match", cypher, actual)
            }
            cypherBlock.semanticEqual = true
        }
    }

    private fun testCypherParams(
        cypherBlock: CodeBlock?,
        cypherParamsBlock: CodeBlock?,
        result: () -> CypherResult
    ): DynamicTest? {
        if (cypherBlock == null || cypherParamsBlock == null) {
            return null
        }
        return DynamicTest.dynamicTest("Test Cypher Params", cypherParamsBlock.uri) {
            val (cypher, params) = result()

            val actualParamsJson = JsonUtils.prettyPrintJson(params)
            if (cypherParamsBlock.content.isBlank()) {
                if (params.isNotEmpty()) {
                    cypherParamsBlock.generatedContent = actualParamsJson
                    Assertions.fail<Any>("No params defined")
                }
                return@dynamicTest
            }
            val expectedCypherParams = JsonUtils.parseJson<Map<String, Any?>>(cypherParamsBlock.content)
            if (!Objects.equals(expectedCypherParams, actualParamsJson)) {
                cypherParamsBlock.generatedContent = actualParamsJson
            }

            val expectedRenamedParameters = CypherUtils.parseNormalized(cypherBlock.content).catalog.renamedParameters

            if (expectedRenamedParameters != null) {
                val actualRenamedParameters = CypherUtils.parseNormalized(cypher).catalog
                    .renamedParameters
                    .map { (k, v) -> v to k }
                    .toMap()
                val remappedExpectedCypherParams = expectedCypherParams.mapKeys { (k, _) ->
                    actualRenamedParameters[expectedRenamedParameters[k]] ?: k
                }
                assertCypherParams(remappedExpectedCypherParams, params)
                cypherBlock.tandemUpdate = cypherParamsBlock
                cypherParamsBlock.tandemUpdate = cypherBlock
            } else {
                assertCypherParams(expectedCypherParams, params)
            }
            cypherParamsBlock.semanticEqual = true
        }
    }

    private fun integrationTest(
        title: String,
        testCase: TestCase,
    ): DynamicNode {
        val graphqlResponse = testCase.graphqlResponse
        val graphqlResponseAssertions = testCase.graphqlResponseAssertions

        val uri = graphqlResponseAssertions?.uri ?: graphqlResponse?.uri
        ?: error("missing graphql response for $title")

        return DynamicTest.dynamicTest("Integration Test", uri) {
            val neo4jAdapter = if (driver == null) {
                Neo4jAdapter.NO_OP
            } else {
                driver.session().use { session ->
                    // clear the database
                    session.run("MATCH (n) DETACH DELETE n")

                    // import test data
                    testCase.setup.testData.firstOrNull()?.content
                        ?.split(";")
                        ?.filter { it.isNotBlank() }
                        ?.forEach { query -> session.run(query) }
                }
                Neo4jDriverAdapter(driver)
            }

            val request = testCase.graphqlRequest?.content
                ?: error("missing graphql for $title")

            val requestParams =
                testCase.graphqlRequestVariables?.content
                    ?.let { JsonUtils.parseJson<Map<String, Any?>>(it) }
                    ?: emptyMap()

            val queryContext = testCase.queryConfig?.content
                ?.let<String, QueryContext?> { config ->
                    return@let JsonUtils.parseJson<QueryContext>(config)
                }
                ?: QueryContext()


            val schema = createSchema(testCase.setup, neo4jAdapter)
            val graphql = GraphQL.newGraphQL(schema).build()
            val result = graphql.execute(
                ExecutionInput.newExecutionInput()
                    .query(request)
                    .variables(requestParams)
                    .graphQLContext(mapOf(QueryContext.KEY to queryContext))
                    .build()
            )
            if (result.errors.isNotEmpty()) {
                val exception =
                    result.errors.filterIsInstance<java.lang.RuntimeException>().firstOrNull()
                        ?: result.errors.filterIsInstance<ExceptionWhileDataFetching>().firstOrNull()?.exception

                Assertions.fail<Any>(result.errors.joinToString("\n") { it.message }, exception)
            }


            val values = result?.getData<Any>()

            if (graphqlResponseAssertions != null) {
                assertWithJsonPath(graphqlResponseAssertions, values)
            } else if (graphqlResponse != null) {

                if (graphqlResponse.content.isEmpty()) {
                    val actualCode = JsonUtils.prettyPrintJson(values)
                    graphqlResponse.generatedContent = actualCode
                } else {
                    val expectedCypherParams = JsonUtils.parseJson<Map<String, Any?>>(graphqlResponse.content)
                    if (!Objects.equals(expectedCypherParams, values)) {
                        val actualCode = JsonUtils.prettyPrintJson(values)
                        graphqlResponse.generatedContent = actualCode
                    }
                    if (graphqlResponse.attributes.containsKey("ignore-order")) {
                        assertEqualIgnoreOrder(expectedCypherParams, values)
                    } else {
                        Assertions.assertThat(values).isEqualTo(expectedCypherParams)
                    }
                }
            }
        }
    }

    class InvalidQueryException(@Suppress("MemberVisibilityCanBePrivate") val error: GraphQLError) :
        RuntimeException(error.message)

    companion object {
        private val DEBUG = System.getProperty("neo4j-graphql-java.debug", "false") == "true"
    }
}
