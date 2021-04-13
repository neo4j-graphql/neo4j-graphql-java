package org.neo4j.graphql.utils

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLSchema
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.graphql.*
import org.neo4j.harness.Neo4j
import org.opentest4j.AssertionFailedError
import java.util.*
import java.util.concurrent.FutureTask
import kotlin.streams.toList

class CypherTestSuite(fileName: String, val neo4j: Neo4j? = null) : AsciiDocTestSuite(
        fileName,
        listOf(
                SCHEMA_CONFIG_MARKER,
                GRAPHQL_MARKER,
                GRAPHQL_VARIABLES_MARKER,
                GRAPHQL_RESPONSE_MARKER,
                QUERY_CONFIG_MARKER,
                CYPHER_PARAMS_MARKER,
                CYPHER_MARKER
        ),
        listOf(SCHEMA_MARKER, SCHEMA_CONFIG_MARKER, TEST_DATA_MARKER)
) {

    override fun testFactory(title: String, globalBlocks: Map<String, ParsedBlock>, codeBlocks: Map<String, ParsedBlock>, ignore: Boolean): List<DynamicNode> {
        val cypherBlock = getOrCreateBlock(codeBlocks, CYPHER_MARKER, "Cypher")

        if (ignore) {
            return Collections.singletonList(DynamicTest.dynamicTest("Test Cypher", cypherBlock?.uri) {
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
            val testData = globalBlocks[TEST_DATA_MARKER]
            val response = getOrCreateBlock(codeBlocks, GRAPHQL_RESPONSE_MARKER, "GraphQL-Response")
            if (testData != null && response != null) {
                tests.add(integrationTest(title, globalBlocks, codeBlocks, testData, response))
            }
        }

        tests.add(testCypher(title, cypherBlock, result))
        tests.add(testCypherParams(codeBlocks, result))

        return tests
    }

    private fun createSchema(
            globalBlocks: Map<String, ParsedBlock>,
            codeBlocks: Map<String, ParsedBlock>,
            dataFetchingInterceptor: DataFetchingInterceptor? = null
    ): GraphQLSchema {
        val schemaString = globalBlocks[SCHEMA_MARKER]?.code()
                ?: throw IllegalStateException("Schema should be defined")
        val schemaConfig = (codeBlocks[SCHEMA_CONFIG_MARKER] ?: globalBlocks[SCHEMA_CONFIG_MARKER])?.code()
            ?.let { return@let MAPPER.readValue(it, SchemaConfig::class.java) }
                ?: SchemaConfig()
        return SchemaBuilder.buildSchema(schemaString, schemaConfig, dataFetchingInterceptor)
    }

    private fun createTransformationTask(
            title: String,
            globalBlocks: Map<String, ParsedBlock>,
            codeBlocks: Map<String, ParsedBlock>
    ): () -> Cypher {
        val transformationTask = FutureTask {

            val schema = createSchema(globalBlocks, codeBlocks)

            val request = codeBlocks[GRAPHQL_MARKER]?.code()
                    ?: throw IllegalStateException("missing graphql for $title")

            val requestParams = codeBlocks[GRAPHQL_VARIABLES_MARKER]?.code()?.parseJsonMap() ?: emptyMap()

            val queryContext = codeBlocks[QUERY_CONFIG_MARKER]?.code()
                ?.let<String, QueryContext?> { config -> return@let MAPPER.readValue(config, QueryContext::class.java) }
                    ?: QueryContext()

            Translator(schema)
                .translate(request, requestParams, queryContext)
                .first()
        }
        return {
            transformationTask.run()
            transformationTask.get()
        }
    }

    private fun printGeneratedQuery(result: () -> Cypher): DynamicTest = DynamicTest.dynamicTest("Generated query") {
        println(result().query)
    }

    private fun printReplacedParameter(result: () -> Cypher): DynamicTest = DynamicTest.dynamicTest("Generated query with params replaced") {
        var queryWithReplacedParams = result().query
        result().params.forEach { (key, value) ->
            queryWithReplacedParams = queryWithReplacedParams.replace("$$key", if (value is String) "'$value'" else value.toString())
        }
        println()
        println("Generated query with params replaced")
        println("------------------------------------")
        println(queryWithReplacedParams)
    }

    private fun testCypher(title: String, cypherBlock: ParsedBlock?, result: () -> Cypher): DynamicTest = DynamicTest.dynamicTest("Test Cypher", cypherBlock?.uri) {
        val cypher = cypherBlock?.code()
                ?: throw IllegalStateException("missing cypher query for $title")
        val expected = cypher.normalize()
        val actual = result().query.normalize()
        if (!Objects.equals(expected, actual)) {
            cypherBlock.adjustedCode = result().query
        }
        if (actual != expected) {
            throw AssertionFailedError("Cypher does not match", cypher, result().query)
        }
    }

    private fun testCypherParams(codeBlocks: Map<String, ParsedBlock>, result: () -> Cypher): DynamicTest {
        val cypherParamsBlock = getOrCreateBlock(codeBlocks, CYPHER_PARAMS_MARKER, "Cypher Params")

        return DynamicTest.dynamicTest("Test Cypher Params", cypherParamsBlock?.uri) {
            val resultParams = result().params

            val actualParams = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(resultParams)
            if (cypherParamsBlock?.code()?.isBlank() == true) {
                if (resultParams.isNotEmpty()) {
                    cypherParamsBlock.adjustedCode = actualParams
                    Assertions.fail<Any>("No params defined")
                }
                return@dynamicTest
            }
            val cypherParams = cypherParamsBlock?.code()?.parseJsonMap() ?: emptyMap()
            val expected = fixNumbers(cypherParams)
            val actual = fixNumbers(resultParams)
            if (!Objects.equals(expected, actual)) {
                cypherParamsBlock?.adjustedCode = actualParams
            }
            Assertions.assertThat(actual).isEqualTo(expected)
        }
    }

    private fun setupDataFetchingInterceptor(testData: ParsedBlock): DataFetchingInterceptor {
        return object : DataFetchingInterceptor {
            override fun fetchData(env: DataFetchingEnvironment, delegate: DataFetcher<Cypher>): Any? = neo4j
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
            globalBlocks: Map<String, ParsedBlock>,
            codeBlocks: Map<String, ParsedBlock>,
            testData: ParsedBlock,
            response: ParsedBlock
    ): DynamicNode = DynamicTest.dynamicTest("Integration Test", response.uri) {
        val dataFetchingInterceptor = setupDataFetchingInterceptor(testData)
        val request = codeBlocks[GRAPHQL_MARKER]?.code()
                ?: throw IllegalStateException("missing graphql for $title")


        val requestParams = codeBlocks[GRAPHQL_VARIABLES_MARKER]?.code()?.parseJsonMap() ?: emptyMap()

        val queryContext = codeBlocks[QUERY_CONFIG_MARKER]?.code()
            ?.let<String, QueryContext?> { config -> return@let MAPPER.readValue(config, QueryContext::class.java) }
                ?: QueryContext()


        val schema = createSchema(globalBlocks, codeBlocks, dataFetchingInterceptor)
        val graphql = GraphQL.newGraphQL(schema).build()
        val result = graphql.execute(ExecutionInput.newExecutionInput()
            .query(request)
            .variables(requestParams)
            .context(queryContext)
            .build())
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
            Assertions.assertThat(actual).isEqualTo(expected)
        }
    }

    companion object {
        private val DEBUG = System.getProperty("neo4j-graphql-java.debug", "false") == "true"

        private const val TEST_DATA_MARKER = "[source,cypher,test-data=true]"
        private const val CYPHER_MARKER = "[source,cypher]"
        private const val GRAPHQL_MARKER = "[source,graphql]"
        private const val GRAPHQL_VARIABLES_MARKER = "[source,json,request=true]"
        private const val GRAPHQL_RESPONSE_MARKER = "[source,json,response=true]"
        private const val QUERY_CONFIG_MARKER = "[source,json,query-config=true]"
        private const val CYPHER_PARAMS_MARKER = "[source,json]"
    }
}
