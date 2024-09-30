package org.neo4j.graphql.utils

import demo.org.neo4j.graphql.utils.InvalidQueryException
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.GraphQLError
import graphql.execution.NonNullableFieldWasNullError
import graphql.schema.GraphQLSchema
import org.assertj.core.api.Assertions
import org.assertj.core.api.InstanceOfAssertFactories
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.cypherdsl.core.renderer.Configuration
import org.neo4j.cypherdsl.core.renderer.Dialect
import org.neo4j.cypherdsl.core.renderer.Renderer
import org.neo4j.cypherdsl.parser.CypherParser
import org.neo4j.cypherdsl.parser.Options
import org.neo4j.driver.internal.InternalIsoDuration
import org.neo4j.driver.types.IsoDuration
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.driver.adapter.Neo4jAdapter
import org.neo4j.harness.Neo4j
import org.opentest4j.AssertionFailedError
import java.math.BigInteger
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import java.util.concurrent.FutureTask
import java.util.function.Consumer
import java.util.regex.Matcher
import java.util.regex.Pattern

class CypherTestSuite(fileName: String, val neo4j: Neo4j? = null) : AsciiDocTestSuite(
    fileName,
    TEST_CASE_MARKERS,
    GLOBAL_MARKERS
) {

    data class CypherResult(val query: String, val params: Map<String, Any?>)

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
                        .withDialect(Dialect.NEO4J_5)
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
        neo4jAdapter: Neo4jAdapter = Neo4jAdapter.NO_OP
    ): GraphQLSchema {
        val schemaString = globalBlocks[SCHEMA_MARKER]?.firstOrNull()?.code()
            ?: throw IllegalStateException("Schema should be defined")
        val schemaConfig = (codeBlocks[SCHEMA_CONFIG_MARKER]?.firstOrNull()
            ?: globalBlocks[SCHEMA_CONFIG_MARKER]?.firstOrNull())?.code()
            ?.let { return@let MAPPER.readValue(it, SchemaConfig::class.java) }
            ?: SchemaConfig()
        return SchemaBuilder.buildSchema(schemaString, schemaConfig, neo4jAdapter)
    }

    private fun createTransformationTask(
        title: String,
        globalBlocks: Map<String, List<ParsedBlock>>,
        codeBlocks: Map<String, List<ParsedBlock>>
    ): () -> List<CypherResult> {
        val transformationTask = FutureTask {

            val cypherResults = mutableListOf<CypherResult>()

            val schema = createSchema(globalBlocks, codeBlocks, object : Neo4jAdapter {

                override fun getDialect(): Neo4jAdapter.Dialect = Neo4jAdapter.Dialect.NEO4J_5

                override fun executeQuery(cypher: String, params: Map<String, Any?>): List<Map<String, Any?>> {
                    cypherResults.add(CypherResult(cypher, params))
                    return emptyList()
                }
            })

            val request = codeBlocks[GRAPHQL_MARKER]?.firstOrNull()?.code()
                ?: throw IllegalStateException("missing graphql for $title")

            val requestParams = codeBlocks[GRAPHQL_VARIABLES_MARKER]?.firstOrNull()?.code()?.parseJsonMap()
                ?: emptyMap()

            val queryContext = codeBlocks[QUERY_CONFIG_MARKER]?.firstOrNull()?.code()
                ?.let<String, QueryContext?> { config -> return@let MAPPER.readValue(config, QueryContext::class.java) }
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
            cypherResults
        }
        return {
            transformationTask.run()
            transformationTask.get()
        }
    }

    private fun printGeneratedQuery(result: () -> List<CypherResult>): DynamicTest =
        DynamicTest.dynamicTest("Generated query") {
            result().forEach { println(it) }
        }

    private fun printReplacedParameter(result: () -> List<CypherResult>): DynamicTest =
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
        result: () -> List<CypherResult>
    ): List<DynamicTest> = cypherBlocks.mapIndexed { index, cypherBlock ->
        var name = "Test Cypher"
        if (cypherBlocks.size > 1) {
            name += " (${index + 1})"
        }
        DynamicTest.dynamicTest(name, cypherBlock.uri) {
            val renderer = Renderer.getRenderer(RENDER_OPTIONS)
            val cypher = cypherBlock.code()
            val expectedNormalized = renderer.render(CypherParser.parse(cypher, PARSE_OPTIONS))
            val actual = (result().getOrNull(index)?.query
                ?: throw IllegalStateException("missing cypher query for $title ($index)"))
            val actualNormalized = renderer.render(CypherParser.parse(actual, PARSE_OPTIONS))

            if (!Objects.equals(expectedNormalized, actual)) {
                cypherBlock.adjustedCode = actual
            }
            if (actualNormalized != expectedNormalized) {
                val SPLITTER =
                    "\n\n~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n~  source query\n~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n\n"
                throw AssertionFailedError(
                    "Cypher does not match",
                    expectedNormalized + SPLITTER + cypher,
                    actualNormalized + SPLITTER + actual
                )
                // TODO
                //  throw AssertionFailedError("Cypher does not match", cypher, actual)
            }
            cypherBlock.semanticEqual = true
        }
    }

    private fun testCypherParams(
        codeBlocks: Map<String, List<ParsedBlock>>,
        result: () -> List<CypherResult>
    ): List<DynamicTest> {
        val cypherParamsBlocks = getOrCreateBlocks(codeBlocks, CYPHER_PARAMS_MARKER, "Cypher Params")

        return cypherParamsBlocks.mapIndexed { index, cypherParamsBlock ->
            var name = "Test Cypher Params"
            if (cypherParamsBlocks.size > 1) {
                name += " (${index + 1})"
            }
            DynamicTest.dynamicTest(name, cypherParamsBlock.uri) {
                val (cypher, params) = result().getOrNull(index)
                    ?: throw IllegalStateException("Expected a cypher query with index $index")

                val actualParamsJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(params)
                if (cypherParamsBlock.code().isBlank()) {
                    if (params.isNotEmpty()) {
                        cypherParamsBlock.adjustedCode = actualParamsJson
                        Assertions.fail<Any>("No params defined")
                    }
                    return@dynamicTest
                }
                val expectedCypherParams = cypherParamsBlock.code().parseJsonMap()
                val expected = fixNumbers(expectedCypherParams)
                val actual = fixNumbers(actualParamsJson.parseJsonMap())
                if (!Objects.equals(expected, actual)) {
                    cypherParamsBlock.adjustedCode = actualParamsJson
                }

                val cypherCodeBlock = codeBlocks[CYPHER_MARKER]?.get(index)
                val expectedRenamedParameters = cypherCodeBlock?.code()
                    ?.let { CypherParser.parse(it, PARSE_OPTIONS).catalog.renamedParameters }

                if (expectedRenamedParameters != null) {
                    val actualRenamedParameters = CypherParser.parse(cypher, PARSE_OPTIONS).catalog
                        .renamedParameters
                        .map { (k, v) -> v to k }
                        .toMap()
                    val remappedExpectedCypherParams = expectedCypherParams.mapKeys { (k, _) ->
                        actualRenamedParameters[expectedRenamedParameters[k]] ?: k
                    }
                    assertCypherParams(remappedExpectedCypherParams, params)
                    cypherCodeBlock.tandemUpdate = cypherParamsBlock
                    cypherParamsBlock.tandemUpdate = cypherCodeBlock
                } else {
                    assertCypherParams(expectedCypherParams, params)
                }
                cypherParamsBlock.semanticEqual = true
            }
        }
    }

    private fun setupNeo4jAdapter(testData: ParsedBlock): Neo4jAdapter {
        return object : Neo4jAdapter {
            override fun executeQuery(cypher: String, params: Map<String, Any?>): List<Map<String, Any?>> {
                if (neo4j == null) {
                    return emptyList()
                }
                val db = neo4j.defaultDatabaseService()

                // cleanup the database
                db.executeTransactionally("MATCH (n) DETACH DELETE n")

                // load the test data
                if (testData.code().isNotBlank()) {
                    testData.code()
                        .split(";")
                        .filter { it.isNotBlank() }
                        .forEach { db.executeTransactionally(it) }
                }

                // execute the query
                return db.executeTransactionally(cypher, params) { result ->
                    result.stream().toList()
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
        val neo4jAdapter = setupNeo4jAdapter(testData)
        val request = codeBlocks[GRAPHQL_MARKER]?.firstOrNull()?.code()
            ?: throw IllegalStateException("missing graphql for $title")


        val requestParams = codeBlocks[GRAPHQL_VARIABLES_MARKER]?.firstOrNull()?.code()?.parseJsonMap() ?: emptyMap()

        val queryContext = codeBlocks[QUERY_CONFIG_MARKER]?.firstOrNull()?.code()
            ?.let<String, QueryContext?> { config -> return@let MAPPER.readValue(config, QueryContext::class.java) }
            ?: QueryContext()


        val schema = createSchema(globalBlocks, codeBlocks, neo4jAdapter)
        val graphql = GraphQL.newGraphQL(schema).build()
        val result = graphql.execute(
            ExecutionInput.newExecutionInput()
                .query(request)
                .variables(requestParams)
                .graphQLContext(mapOf(QueryContext.KEY to queryContext))
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
                .satisfies(Consumer { it.forEach { (key, value) -> assertEqualIgnoreOrder(expected[key], value) } })

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
        private val CONVERT_NUMBER = System.getProperty("neo4j-graphql-java.convert-number", "true") == "true"

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

        private val DURATION_PATTERN: Pattern = Pattern.compile("^P(.*?)(?:T(.*))?$")

        private val PARSE_OPTIONS = Options.newOptions()
            .createSortedMaps(true)
            .alwaysCreateRelationshipsLTR(true)
            .build()

        private val RENDER_OPTIONS = Configuration.newConfig()
            .withPrettyPrint(true)
            .withGeneratedNames(
                setOf(
                    Configuration.GeneratedNames.ALL_ALIASES,
                    Configuration.GeneratedNames.PARAMETER_NAMES,
                    Configuration.GeneratedNames.ENTITY_NAMES,
                )
            )
            .build()

        private fun assertCypherParams(expected: Map<String, Any?>, actual: Map<String, Any?>) {
            Assertions.assertThat(actual).asInstanceOf(InstanceOfAssertFactories.MAP)
                .hasSize(expected.size)
                .containsOnlyKeys(*expected.keys.toTypedArray())
                .allSatisfy({ key, value -> assertCypherParam(expected[key], value) })
        }

        private fun assertCypherParam(expected: Any?, actual: Any?) {
            when (expected) {
                is Int -> when (actual) {
                    is Double -> {
                        Assertions.assertThat(actual).isEqualTo(expected.toDouble())
                        return
                    }

                    is Long -> {
                        Assertions.assertThat(actual).isEqualTo(expected.toLong())
                        return
                    }

                    is BigInteger -> {
                        Assertions.assertThat(actual).isEqualTo(expected.toBigInteger())
                        return
                    }
                }

                is String -> {
                    try {
                        val expectedDate = ZonedDateTime.parse(expected)
                        val actualDate = ZonedDateTime.parse(actual as String)
                        Assertions.assertThat(actualDate).isEqualTo(expectedDate)
                        return
                    } catch (ignore: DateTimeParseException) {
                    }
                    try {
                        val expectedDate = LocalDateTime.parse(expected)
                        val actualDate = LocalDateTime.parse(actual as String)
                        Assertions.assertThat(actualDate).isEqualTo(expectedDate)
                        return
                    } catch (ignore: DateTimeParseException) {
                    }
                    try {
                        val expectedTime = LocalTime.parse(expected, DateTimeFormatter.ISO_TIME)
                        val actualTime = LocalTime.parse(actual as String)
                        Assertions.assertThat(actualTime).isEqualTo(expectedTime)
                        return
                    } catch (ignore: DateTimeParseException) {
                    }
                    try {
                        val expectedTime = LocalTime.parse(expected, DateTimeFormatter.ISO_TIME)
                        val actualTime = LocalTime.parse(actual as String)
                        Assertions.assertThat(actualTime).isEqualTo(expectedTime)
                        return
                    } catch (ignore: DateTimeParseException) {
                    }
                    try {
                        parseDuration(expected)?.let { expectedDuration ->
                            parseDuration(actual as String)?.let { actualDuration ->
                                Assertions.assertThat(actualDuration).isEqualTo(expectedDuration)
                                return
                            }
                        }
                    } catch (ignore: DateTimeParseException) {
                    }
                }

                is Map<*, *> -> {
                    if (actual is Number) {
                        val low = expected["low"] as? Number
                        val high = expected["high"] as? Number
                        if (low != null && high != null && expected.size == 2) {
                            // this is to convert the js bigint into a java long
                            val highLong = high.toLong() shl 32
                            val lowLong = low.toLong() and 0xFFFFFFFFL
                            val expectedLong = highLong or lowLong
                            when (actual) {
                                is BigInteger -> Assertions.assertThat(actual.toLong()).isEqualTo(expectedLong)
                                is Int -> Assertions.assertThat(actual.toLong()).isEqualTo(expectedLong)
                                is Long -> Assertions.assertThat(actual).isEqualTo(expectedLong)
                                else -> Assertions.fail<Any>("Unexpected type ${actual::class.java}")
                            }
                            return
                        }
                    }
                    if (actual is Map<*, *>) {
                        Assertions.assertThat(actual).hasSize(expected.count())
                        expected.forEach { key, value ->
                            assertCypherParam(value, actual.get(key))
                        }
                        return
                    }
                }

                is Iterable<*> -> {
                    if (actual is Iterable<*>) {
                        Assertions.assertThat(actual).hasSize(expected.count())
                        expected.forEachIndexed { index, expectedValue ->
                            assertCypherParam(expectedValue, actual.elementAt(index))
                        }
                        return
                    }
                }
            }
            Assertions.assertThat(actual).isEqualTo(expected)
        }

        private fun fixNumber(v: Any?): Any? = when (v) {
            is Double -> v.toDouble().convertNumber()
            is Float -> v.toDouble().convertNumber()
            is Int -> v.toLong()
            is BigInteger -> v.toLong()
            is Iterable<*> -> v.map { fixNumber(it) }
            is Sequence<*> -> v.map { fixNumber(it) }
            is Map<*, *> -> {
                val low = v["low"] as? Number
                val high = v["high"] as? Number
                if (low != null && high != null && v.size == 2) {
                    // this is to convert th js bigint into a java long
                    val highLong = high.toLong() shl 32
                    val lowLong = low.toLong() and 0xFFFFFFFFL
                    highLong or lowLong
                } else {
                    v.mapValues { fixNumber(it.value) }
                }
            }

            else -> v
        }

        private fun fixNumbers(params: Map<String, Any?>) = params.mapValues { (_, v) -> fixNumber(v) }
        private fun Double.convertNumber() = when {
            CONVERT_NUMBER && this == toLong().toDouble() -> toLong()
            else -> this
        }

        private fun parseDuration(text: String): IsoDuration? {
            val matcher: Matcher = DURATION_PATTERN.matcher(text)
            if (!matcher.find()) {
                return null
            }
            val periodString = matcher.group(1)
            val timeString = matcher.group(2)
            val period = if (!periodString.isNullOrBlank()) Period.parse("P$periodString") else Period.ZERO
            val duration = if (!timeString.isNullOrBlank()) Duration.parse("PT$timeString") else Duration.ZERO
            return InternalIsoDuration(period.toTotalMonths(), period.days.toLong(), duration.seconds, duration.nano)
        }


    }
}
