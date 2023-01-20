package org.neo4j.graphql.utils

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLSchema
import org.assertj.core.api.Assertions
import org.assertj.core.api.InstanceOfAssertFactories
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.cypherdsl.core.renderer.Configuration
import org.neo4j.cypherdsl.core.renderer.Renderer
import org.neo4j.cypherdsl.parser.CypherParser
import org.neo4j.cypherdsl.parser.Options
import org.neo4j.graphql.*
import org.neo4j.harness.Neo4j
import org.opentest4j.AssertionFailedError
import java.util.*
import java.util.concurrent.FutureTask
import java.util.function.Consumer

class CypherTestSuite(fileName: String, val neo4j: Neo4j? = null) : AsciiDocTestSuite(
    fileName,
    TEST_CASE_MARKERS,
    GLOBAL_MARKERS
) {

    override fun testFactory(
        title: String,
        globalBlocks: Map<String, List<ParsedBlock>>,
        codeBlocks: Map<String, List<ParsedBlock>>,
        ignore: Boolean
    ): List<DynamicNode> {
        val cypherBlocks = getOrCreateBlocks(codeBlocks, CYPHER_MARKER, "Cypher")

        if (ignore) {
            return Collections.singletonList(DynamicTest.dynamicTest("Test Cypher", cypherBlocks.firstOrNull()?.uri) {
                Assumptions.assumeFalse(true)
            })
        }

        val result = createTransformationTask(title, globalBlocks, codeBlocks)

        val tests = mutableListOf<DynamicNode>()
        if (DEBUG) {
            tests.add(printGeneratedQuery(result))
            tests.add(printReplacedParameter(result))
        }
        if (neo4j != null) {
            val testData = globalBlocks[TEST_DATA_MARKER]?.firstOrNull()
            var response = codeBlocks[GRAPHQL_RESPONSE_IGNORE_ORDER_MARKER]?.firstOrNull()
            var ignoreOrder = false
            if (response != null) {
                ignoreOrder = true
            } else {
                response = getOrCreateBlocks(codeBlocks, GRAPHQL_RESPONSE_MARKER, "GraphQL-Response").firstOrNull()
            }
            if (testData != null && response != null) {
                tests.add(integrationTest(title, globalBlocks, codeBlocks, testData, response, ignoreOrder))
            }
        }
        if (REFORMAT_TEST_FILE) {
            cypherBlocks.forEach {
                val statement = CypherParser.parse(it.code(), Options.defaultOptions())
                val query = Renderer.getRenderer(
                    Configuration
                        .newConfig()
                        .withIndentStyle(Configuration.IndentStyle.TAB)
                        .withPrettyPrint(true)
                        .build()
                ).render(statement)
                it.reformattedCode = query
            }
            getOrCreateBlocks(codeBlocks, CYPHER_PARAMS_MARKER, "Cypher Params").forEach {
                val cypherParams = it.code().parseJsonMap()
                it.reformattedCode = MAPPER
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(cypherParams.toSortedMap())
            }

        }

        tests.addAll(testCypher(title, cypherBlocks, result))
        tests.addAll(testCypherParams(codeBlocks, result))

        return tests
    }

    private fun createSchema(
        globalBlocks: Map<String, List<ParsedBlock>>,
        codeBlocks: Map<String, List<ParsedBlock>>,
        dataFetchingInterceptor: DataFetchingInterceptor? = null
    ): GraphQLSchema {
        val schemaString = globalBlocks[SCHEMA_MARKER]?.firstOrNull()?.code()
            ?: throw IllegalStateException("Schema should be defined")
        val schemaConfig = (codeBlocks[SCHEMA_CONFIG_MARKER]?.firstOrNull()
            ?: globalBlocks[SCHEMA_CONFIG_MARKER]?.firstOrNull())?.code()
            ?.let { return@let MAPPER.readValue(it, SchemaConfig::class.java) }
            ?: SchemaConfig()
        return SchemaBuilder.buildSchema(schemaString, schemaConfig, dataFetchingInterceptor)
    }

    private fun createTransformationTask(
        title: String,
        globalBlocks: Map<String, List<ParsedBlock>>,
        codeBlocks: Map<String, List<ParsedBlock>>
    ): () -> List<OldCypher> {
        val transformationTask = FutureTask {

            val schema = createSchema(globalBlocks, codeBlocks)

            val request = codeBlocks[GRAPHQL_MARKER]?.firstOrNull()?.code()
                ?: throw IllegalStateException("missing graphql for $title")

            val requestParams = codeBlocks[GRAPHQL_VARIABLES_MARKER]?.firstOrNull()?.code()?.parseJsonMap()
                ?: emptyMap()

            val queryContext = codeBlocks[QUERY_CONFIG_MARKER]?.firstOrNull()?.code()
                ?.let<String, QueryContext?> { config -> return@let MAPPER.readValue(config, QueryContext::class.java) }
                ?: QueryContext()

            Translator(schema)
                .translate(request, requestParams, queryContext)
        }
        return {
            transformationTask.run()
            transformationTask.get()
        }
    }

    private fun printGeneratedQuery(result: () -> List<OldCypher>): DynamicTest =
        DynamicTest.dynamicTest("Generated query") {
            result().forEach { println(it) }
        }

    private fun printReplacedParameter(result: () -> List<OldCypher>): DynamicTest =
        DynamicTest.dynamicTest("Generated query with params replaced") {
            result().forEach {
                var queryWithReplacedParams = it.query
                it.params.forEach { (key, value) ->
                    queryWithReplacedParams =
                        queryWithReplacedParams.replace("$$key", if (value is String) "'$value'" else value.toString())
                }
                println()
                println("Generated query with params replaced")
                println("------------------------------------")
                println(queryWithReplacedParams)
            }
        }

    private fun testCypher(
        title: String,
        cypherBlocks: List<ParsedBlock>,
        result: () -> List<OldCypher>
    ): List<DynamicTest> = cypherBlocks.mapIndexed { index, cypherBlock ->
        var name = "Test Cypher"
        if (cypherBlocks.size > 1) {
            name += " (${index + 1})"
        }
        DynamicTest.dynamicTest(name, cypherBlock.uri) {
            val cypher = cypherBlock.code()
            val expected = cypher.normalize()
            val actual = (result().getOrNull(index)?.query
                ?: throw IllegalStateException("missing cypher query for $title ($index)"))
            val actualNormalized = actual.normalize()

            if (!Objects.equals(expected, actual)) {
                cypherBlock.adjustedCode = actual
            }
            if (actualNormalized != expected) {
                throw AssertionFailedError("Cypher does not match", cypher, actual)
            }
        }
    }

    private fun testCypherParams(
        codeBlocks: Map<String, List<ParsedBlock>>,
        result: () -> List<OldCypher>
    ): List<DynamicTest> {
        val cypherParamsBlocks = getOrCreateBlocks(codeBlocks, CYPHER_PARAMS_MARKER, "Cypher Params")

        return cypherParamsBlocks.mapIndexed { index, cypherParamsBlock ->
            var name = "Test Cypher Params"
            if (cypherParamsBlocks.size > 1) {
                name += " (${index + 1})"
            }
            DynamicTest.dynamicTest(name, cypherParamsBlock.uri) {
                val resultParams = result().getOrNull(index)?.params
                    ?: throw IllegalStateException("Expected a cypher query with index $index")

                val actualParams = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(resultParams)
                if (cypherParamsBlock.code().isBlank()) {
                    if (resultParams.isNotEmpty()) {
                        cypherParamsBlock.adjustedCode = actualParams
                        Assertions.fail<Any>("No params defined")
                    }
                    return@dynamicTest
                }
                val cypherParams = cypherParamsBlock.code().parseJsonMap()
                val expected = fixNumbers(cypherParams)
                val actual = fixNumbers(actualParams.parseJsonMap())
                if (!Objects.equals(expected, actual)) {
                    cypherParamsBlock.adjustedCode = actualParams
                }
                Assertions.assertThat(actual).isEqualTo(expected)
            }
        }
    }

    private fun setupDataFetchingInterceptor(testData: ParsedBlock): DataFetchingInterceptor {
        return object : DataFetchingInterceptor {
            override fun fetchData(env: DataFetchingEnvironment, delegate: DataFetcher<OldCypher>): Any? = neo4j
                ?.defaultDatabaseService()?.let { db ->
                    db.executeTransactionally("MATCH (n) DETACH DELETE n")
                    if (testData.code().isNotBlank()) {
                        testData.code()
                            .split(";")
                            .filter { it.isNotBlank() }
                            .forEach { db.executeTransactionally(it) }
                    }
                    val (cypher, params, type, variable) = delegate.get(env)
                    return db.executeTransactionally(cypher, params) { result ->
                        result.stream().map { it[variable] }.let {
                            when {
                                type?.isList() == true -> it.toList()
                                else -> it.findFirst().orElse(null)
                            }
                        }

                    }
                }
        }
    }

    private fun integrationTest(
        title: String,
        globalBlocks: Map<String, List<ParsedBlock>>,
        codeBlocks: Map<String, List<ParsedBlock>>,
        testData: ParsedBlock,
        response: ParsedBlock,
        ignoreOrder: Boolean
    ): DynamicNode = DynamicTest.dynamicTest("Integration Test", response.uri) {
        val dataFetchingInterceptor = setupDataFetchingInterceptor(testData)
        val request = codeBlocks[GRAPHQL_MARKER]?.firstOrNull()?.code()
            ?: throw IllegalStateException("missing graphql for $title")


        val requestParams = codeBlocks[GRAPHQL_VARIABLES_MARKER]?.firstOrNull()?.code()?.parseJsonMap() ?: emptyMap()

        val queryContext = codeBlocks[QUERY_CONFIG_MARKER]?.firstOrNull()?.code()
            ?.let<String, QueryContext?> { config -> return@let MAPPER.readValue(config, QueryContext::class.java) }
            ?: QueryContext()


        val schema = createSchema(globalBlocks, codeBlocks, dataFetchingInterceptor)
        val graphql = GraphQL.newGraphQL(schema).build()
        val result = graphql.execute(
            ExecutionInput.newExecutionInput()
                .query(request)
                .variables(requestParams)
                .graphQLContext(mapOf(Constants.NEO4J_QUERY_CONTEXT to queryContext))
                .context(queryContext)
                .build()
        )
        Assertions.assertThat(result.errors).isEmpty()

        val values = result?.getData<Any>()

        if (response.code.isEmpty()) {
            val actualCode = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(values)
            response.adjustedCode = actualCode
        } else {
            val expected = fixNumbers(response.code().parseJsonMap())
            val actual = fixNumber(values)
            if (!Objects.equals(expected, actual)) {
                val actualCode = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(values)
                response.adjustedCode = actualCode
            }
            if (ignoreOrder) {
                assertEqualIgnoreOrder(expected, actual)
            } else {
                Assertions.assertThat(actual).isEqualTo(expected)
            }
        }
    }

    private fun assertEqualIgnoreOrder(expected: Any?, actual: Any?) {
        when (expected) {
            is Map<*, *> -> Assertions.assertThat(actual).asInstanceOf(InstanceOfAssertFactories.MAP)
                .hasSize(expected.size)
                .containsOnlyKeys(*expected.keys.toTypedArray())
                .satisfies { it.forEach { (key, value) -> assertEqualIgnoreOrder(expected[key], value) } }

            is Collection<*> -> {
                val assertions: List<Consumer<Any>> =
                    expected.map { e -> Consumer<Any> { a -> assertEqualIgnoreOrder(e, a) } }
                Assertions.assertThat(actual).asInstanceOf(InstanceOfAssertFactories.LIST)
                    .hasSize(expected.size)
                    .satisfiesExactlyInAnyOrder(*assertions.toTypedArray())
            }

            else -> Assertions.assertThat(actual).isEqualTo(expected)
        }
    }

    companion object {
        private val DEBUG = System.getProperty("neo4j-graphql-java.debug", "false") == "true"

        private const val TEST_DATA_MARKER = "[source,cypher,test-data=true]"
        private const val CYPHER_MARKER = "[source,cypher]"
        private const val GRAPHQL_MARKER = "[source,graphql]"
        private const val GRAPHQL_VARIABLES_MARKER = "[source,json,request=true]"
        private const val GRAPHQL_RESPONSE_MARKER = "[source,json,response=true]"
        private const val GRAPHQL_RESPONSE_IGNORE_ORDER_MARKER = "[source,json,response=true,ignore-order]"
        private const val QUERY_CONFIG_MARKER = "[source,json,query-config=true]"
        private const val CYPHER_PARAMS_MARKER = "[source,json]"

        private val TEST_CASE_MARKERS: List<String> = listOf(
            SCHEMA_CONFIG_MARKER,
            GRAPHQL_MARKER,
            GRAPHQL_VARIABLES_MARKER,
            GRAPHQL_RESPONSE_MARKER,
            GRAPHQL_RESPONSE_IGNORE_ORDER_MARKER,
            QUERY_CONFIG_MARKER,
            CYPHER_PARAMS_MARKER,
            CYPHER_MARKER
        )
        private val GLOBAL_MARKERS: List<String> = listOf(SCHEMA_MARKER, SCHEMA_CONFIG_MARKER, TEST_DATA_MARKER)
    }
}
