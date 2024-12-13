package org.neo4j.graphql.utils

import com.fasterxml.jackson.module.kotlin.readValue
import com.jayway.jsonpath.JsonPath
import demo.org.neo4j.graphql.utils.InvalidQueryException
import demo.org.neo4j.graphql.utils.asciidoc.ast.CodeBlock
import demo.org.neo4j.graphql.utils.asciidoc.ast.Section
import demo.org.neo4j.graphql.utils.asciidoc.ast.Table
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
import org.assertj.core.api.InstanceOfAssertFactories
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.cypherdsl.core.renderer.Configuration
import org.neo4j.cypherdsl.core.renderer.Dialect
import org.neo4j.cypherdsl.core.renderer.Renderer
import org.neo4j.cypherdsl.parser.CypherParser
import org.neo4j.cypherdsl.parser.Options
import org.neo4j.driver.Driver
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.custom_resolver.TestDataFetcher
import org.neo4j.graphql.driver.adapter.Neo4jAdapter
import org.neo4j.graphql.driver.adapter.Neo4jDriverAdapter
import org.neo4j.graphql.scalars.TemporalScalar
import org.opentest4j.AssertionFailedError
import org.threeten.extra.PeriodDuration
import java.io.File
import java.io.FileWriter
import java.math.BigInteger
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAccessor
import java.util.*
import java.util.concurrent.FutureTask
import java.util.function.Consumer
import kotlin.reflect.full.findAnnotation

class CypherTestSuite(fileName: String, val driver: Driver? = null, createMissingBlocks: Boolean = true) :
    AsciiDocTestSuite<CypherTestSuite.TestCase>(
        fileName,
        listOf(
            matcher("cypher", exactly = true) { t, c -> t.cypher.add(c) },
            matcher("json", exactly = true) { t, c -> t.cypherParams.add(c) },
            matcher("graphql", exactly = true, setter = TestCase::graphqlRequest),
            matcher("json", mapOf("request" to "true"), setter = TestCase::graphqlRequestVariables),
            matcher("json", mapOf("response" to "true"), setter = TestCase::graphqlResponse),
            matcher("json", mapOf("query-config" to "true"), setter = TestCase::queryConfig),
        ),
        createMissingBlocks,
    ) {

    data class TestCase(
        var schema: CodeBlock,
        var schemaConfig: CodeBlock?,
        var testData: List<CodeBlock>,
        var customResolver: CodeBlock?,
        var cypher: MutableList<CodeBlock> = mutableListOf(),
        var cypherParams: MutableList<CodeBlock> = mutableListOf(),
        var graphqlRequest: CodeBlock? = null,
        var graphqlRequestVariables: CodeBlock? = null,
        var graphqlResponse: CodeBlock? = null,
        var graphqlResponseAssertions: Table? = null,
        var queryConfig: CodeBlock? = null,
    )

    data class CypherResult(val query: String, val params: Map<String, Any?>)

    override fun createTestCase(section: Section): TestCase? {
        val schema = findSetupCodeBlocks(section, "graphql", mapOf("schema" to "true")).firstOrNull() ?: return null
        val schemaConfig = findSetupCodeBlocks(section, "json", mapOf("schema-config" to "true")).firstOrNull()
        val testData = findSetupCodeBlocks(section, "cypher", mapOf("test-data" to "true"))
        val customResolver = findSetupCodeBlocks(section, "kotlin").firstOrNull()

        return TestCase(schema, schemaConfig, testData, customResolver)
    }

    override fun setTableData(testCase: TestCase, table: Table) {
        if (table.attributes.containsKey("response")) {
            testCase.graphqlResponseAssertions = table
        }
    }

    override fun addAdditionalTests(tests: MutableList<DynamicNode>) {
        if (ADD_IGNORE_ORDER_TO_INTEGRATION_TESTS) {
            tests += DynamicTest.dynamicTest("Create ignore-order", srcLocation, this::reformatMarker)
        }
    }

    override fun createTests(testCase: TestCase, section: Section, ignoreReason: String?): List<DynamicNode> {
        if (testCase.graphqlRequest == null) {
            return emptyList()
        }
        if (ignoreReason != null) {
            return listOf(DynamicTest.dynamicTest("Test Cypher", testCase.cypher.firstOrNull()?.uri) {
                Assumptions.assumeFalse(true) { ignoreReason }
            })
        }

        val result = createTransformationTask(testCase)

        val tests = mutableListOf<DynamicNode>()

        if (ADD_IGNORE_ORDER_TO_INTEGRATION_TESTS) {
            val hasOrder = testCase.cypher.any { it.content.contains("ORDER BY") }
            val graphqlResponse = testCase.graphqlResponse
            if (!hasOrder && graphqlResponse != null
                && hasArrayWithMoreThenOneItems(MAPPER.readValue<Any>(graphqlResponse.content))
            ) {
                graphqlResponse.adjustedAttributes = graphqlResponse.attributes.toMutableMap()
                    .also { it["ignore-order"] = null }
            }
        }

        if (DEBUG) {
            tests.add(printGeneratedQuery(result))
            tests.add(printReplacedParameter(result))
        }
        if (driver != null) {
            val testData = testCase.testData.firstOrNull()
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
        if (REFORMAT_TEST_FILE) {
            testCase.cypher.forEach { cypher ->
                val statement = CypherParser.parse(cypher.content, Options.defaultOptions())
                val query = Renderer.getRenderer(
                    Configuration
                        .newConfig()
                        .withDialect(Dialect.NEO4J_5)
                        .withIndentStyle(Configuration.IndentStyle.TAB)
                        .withPrettyPrint(true)
                        .build()
                ).render(statement)
                cypher.reformattedContent = query
            }

            (testCase.cypherParams.takeIf { it.isNotEmpty() }
                ?: createCodeBlock(testCase.cypher.first(), "json", "Cypher Params")?.let { listOf(it) }
                ?: emptyList())
                .filter { it.content.isNotBlank() }
                .forEach { params ->
                    val cypherParams = params.content.parseJsonMap()
                    params.reformattedContent = MAPPER
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(cypherParams.toSortedMap())
                }
        }

        tests.addAll(testCypher(section.title, testCase.cypher, result))
        tests.addAll(testCypherParams(testCase.cypher, testCase.cypherParams, result))

        return tests
    }

    private fun hasArrayWithMoreThenOneItems(value: Any): Boolean {
        when (value) {
            is Map<*, *> -> {
                return value.any {
                    val mapValue = it.value
                    mapValue != null && hasArrayWithMoreThenOneItems(mapValue)
                }
            }

            is Collection<*> -> {
                return value.size > 1 || value.filterNotNull().any { hasArrayWithMoreThenOneItems(it) }
            }
        }
        return false
    }

    private fun createSchema(
        schemaBlock: CodeBlock,
        schemaConfigBlock: CodeBlock?,
        customResolver: CodeBlock?,
        neo4jAdapter: Neo4jAdapter = Neo4jAdapter.NO_OP
    ): GraphQLSchema {
        val schemaString = schemaBlock.content
        val schemaConfig = schemaConfigBlock?.content
            ?.let { return@let MAPPER.readValue(it, SchemaConfig::class.java) }
            ?: SchemaConfig()
        val codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry()

        customResolver?.let { registerTestResolver(it, codeRegistryBuilder) }

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
        val resolvedFile = srcLocation.resolve(path)
        val className = File(resolvedFile)
            .toRelativeString(File("src/test/kotlin").absoluteFile)
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

    private fun createTransformationTask(testCase: TestCase): () -> List<CypherResult> {
        val transformationTask = FutureTask {

            val cypherResults = mutableListOf<CypherResult>()

            val schema =
                createSchema(
                    testCase.schema,
                    testCase.schemaConfig,
                    testCase.customResolver,
                    object : Neo4jAdapter {

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

            val requestParams = testCase.graphqlRequestVariables?.content?.parseJsonMap()
                ?: emptyMap()

            val queryContext = testCase.queryConfig?.content
                ?.let<String, QueryContext?> { config ->
                    return@let MAPPER.readValue(
                        config,
                        QueryContext::class.java
                    )
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

    private fun testCypher(
        title: String,
        cypherBlocks: List<CodeBlock>,
        result: () -> List<CypherResult>
    ): List<DynamicTest> = cypherBlocks.mapIndexed { index, cypherBlock ->
        var name = "Test Cypher"
        if (cypherBlocks.size > 1) {
            name += " (${index + 1})"
        }
        DynamicTest.dynamicTest(name, cypherBlock.uri) {
            val renderer = Renderer.getRenderer(RENDER_OPTIONS)
            val cypher = cypherBlock.content
            val expectedNormalized =
                if (cypher.isNotBlank()) renderer.render(CypherParser.parse(cypher, PARSE_OPTIONS)) else ""
            val actual = (result().getOrNull(index)?.query
                ?: throw IllegalStateException("missing cypher query for $title ($index)"))
            val actualNormalized = renderer.render(CypherParser.parse(actual, PARSE_OPTIONS))

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
        cypherBlocks: List<CodeBlock>,
        cypherParamsBlocks: List<CodeBlock>,
        result: () -> List<CypherResult>
    ): List<DynamicTest> {
        return cypherParamsBlocks.mapIndexed { index, cypherParamsBlock ->
            var name = "Test Cypher Params"
            if (cypherParamsBlocks.size > 1) {
                name += " (${index + 1})"
            }
            DynamicTest.dynamicTest(name, cypherParamsBlock.uri) {
                val (cypher, params) = result().getOrNull(index)
                    ?: throw IllegalStateException("Expected a cypher query with index $index")

                val actualParamsJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(params)
                if (cypherParamsBlock.content.isBlank()) {
                    if (params.isNotEmpty()) {
                        cypherParamsBlock.generatedContent = actualParamsJson
                        Assertions.fail<Any>("No params defined")
                    }
                    return@dynamicTest
                }
                val expectedCypherParams = cypherParamsBlock.content.parseJsonMap()
                val expected = fixNumbers(expectedCypherParams)
                val actual = fixNumbers(actualParamsJson.parseJsonMap())
                if (!Objects.equals(expected, actual)) {
                    cypherParamsBlock.generatedContent = actualParamsJson
                }

                val cypherCodeBlock = cypherBlocks.getOrNull(index)
                val expectedRenamedParameters = cypherCodeBlock?.content
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
                    testCase.testData.firstOrNull()?.content
                        ?.split(";")
                        ?.filter { it.isNotBlank() }
                        ?.forEach { query -> session.run(query) }
                }
                Neo4jDriverAdapter(driver)
            }

            val request = testCase.graphqlRequest?.content
                ?: error("missing graphql for $title")

            val requestParams =
                testCase.graphqlRequestVariables?.content?.parseJsonMap() ?: emptyMap()

            val queryContext = testCase.queryConfig?.content
                ?.let<String, QueryContext?> { config ->
                    return@let MAPPER.readValue(
                        config,
                        QueryContext::class.java
                    )
                }
                ?: QueryContext()


            val schema = createSchema(testCase.schema, testCase.schemaConfig, testCase.customResolver, neo4jAdapter)
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
                    val actualCode = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(values)
                    graphqlResponse.generatedContent = actualCode
                } else {
                    val expected = fixNumbers(graphqlResponse.content.parseJsonMap())
                    val actual = fixNumber(values)
                    if (!Objects.equals(expected, actual)) {
                        val actualCode = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(values)
                        graphqlResponse.generatedContent = actualCode
                    }
                    if (graphqlResponse.attributes.containsKey("ignore-order")) {
                        assertEqualIgnoreOrder(expected, actual)
                    } else {
                        Assertions.assertThat(actual).isEqualTo(expected)
                    }
                }
            }
        }
    }

    private fun assertWithJsonPath(table: Table, result: Any?) {
        for (record in table.records) {
            val jsonPath = record.get("Path")
            val condition = record.get("Condition")
            val expectedValue = record.get("Expected Value")

            val actualValue = JsonPath.read<Any>(result, jsonPath)

            // try to convert the expected value to the type of the current value
            val typedExpectedValue = if (expectedValue.isNullOrBlank()) {
                null
            } else when (actualValue) {
                is Int -> expectedValue.toInt()
                else -> expectedValue
            }

            val assertion = Assertions.assertThat(actualValue).describedAs(jsonPath)
            when (condition) {
                "==" -> assertion.isEqualTo(typedExpectedValue)
                "notEmpty" -> {
                    if (actualValue is Collection<*>) {
                        assertion.asInstanceOf(InstanceOfAssertFactories.COLLECTION).isNotEmpty()
                    } else if (actualValue is String) {
                        assertion.asInstanceOf(InstanceOfAssertFactories.STRING).isNotEmpty()
                    } else {
                        TODO()
                    }
                }

                else -> TODO("$condition not implemented")
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


    private fun reformatMarker() {
        val content = generateAdjustedFileContent({ it.content }, { it.adjustedAttributes != null })
        FileWriter(File("src/test/resources/", fileName)).use { it.write(content) }
    }

    companion object {
        private val DEBUG = System.getProperty("neo4j-graphql-java.debug", "false") == "true"
        private val CONVERT_NUMBER = System.getProperty("neo4j-graphql-java.convert-number", "true") == "true"
        private val ADD_IGNORE_ORDER_TO_INTEGRATION_TESTS =
            System.getProperty("neo4j-graphql-java.add-ignore-order-to-integration-tests", "false") == "true"

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
                    when (actual) {
                        is TemporalAccessor -> {
                            Assertions.assertThat(actual).isEqualTo(TemporalScalar.parse(expected))
                            return
                        }
                    }
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
                        val expectedTime = LocalDate.parse(expected, DateTimeFormatter.ISO_DATE)
                        val actualTime = LocalDate.parse(actual as String)
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
                        PeriodDuration.parse(expected)?.let { expectedDuration ->
                            when (actual) {
                                is PeriodDuration -> Assertions.assertThat(actual).isEqualTo(expectedDuration)
                                is Period -> Assertions.assertThat(PeriodDuration.of(actual))
                                    .isEqualTo(expectedDuration)

                                is Duration -> Assertions.assertThat(PeriodDuration.of(actual))
                                    .isEqualTo(expectedDuration)

                                is String -> Assertions.assertThat(actual).isEqualTo(expected)
                                else -> Assertions.fail<Any>("Unexpected type ${actual!!::class.java}")
                            }
                            return
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
    }
}
