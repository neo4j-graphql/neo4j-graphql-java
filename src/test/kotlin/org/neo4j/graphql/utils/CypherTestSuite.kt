package org.neo4j.graphql.utils

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.graphql.Cypher
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.Translator
import java.util.*
import java.util.concurrent.FutureTask
import java.util.stream.Stream

class CypherTestSuite(fileName: String) : AsciiDocTestSuite(fileName) {

    fun run(): Stream<DynamicNode> {
        return parse(linkedSetOf(
                "[source,graphql]",
                "[source,json,request=true]",
                "[source,json,config=true]",
                "[source,json]",
                "[source,cypher]"
        ))
    }

    override fun testFactory(title: String, schema: String, codeBlocks: Map<String, ParsedBlock>, ignore: Boolean): List<DynamicNode> {
        val cypherBlock = codeBlocks["[source,cypher]"]

        if (ignore) {
            return Collections.singletonList(DynamicTest.dynamicTest("Test Cypher", cypherBlock?.uri) {
                Assumptions.assumeFalse(true)
            })
        }

        val result = createTransformationTask(title, schema, codeBlocks)

        val tests = mutableListOf<DynamicNode>()
        if (DEBUG) {
            tests.add(printGeneratedQuery(result))
            tests.add(printReplacedParameter(result))
        }

        tests.add(testCypher(title, cypherBlock, result))
        tests.add(testCypherParams(codeBlocks, result))

        return tests
    }

    private fun createTransformationTask(title: String, schema: String, codeBlocks: Map<String, ParsedBlock>): () -> Cypher {
        val transformationTask = FutureTask {
            val requestBlock = codeBlocks["[source,graphql]"]
            val request = requestBlock?.code?.trim()?.toString()
                    ?: throw IllegalStateException("missing graphql for $title")

            val requestParamsBlock = codeBlocks["[source,json,request=true]"]
            val requestParams = requestParamsBlock?.code?.toString()?.parseJsonMap() ?: emptyMap()

            val configBlock = codeBlocks["[source,json,config=true]"]
            val config = configBlock?.code?.let<StringBuilder, QueryContext?> { config -> return@let MAPPER.readValue<QueryContext>(config.toString(), QueryContext::class.java) }

            val ctx = config ?: QueryContext()
            Translator(SchemaBuilder.buildSchema(schema))
                .translate(request, requestParams, ctx)
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
        val cypher = cypherBlock?.code?.trim()?.toString()
                ?: throw IllegalStateException("missing cypher query for $title")
        Assertions.assertEquals(cypher.normalize(), result().query.normalize())
    }

    private fun testCypherParams(codeBlocks: Map<String, ParsedBlock>, result: () -> Cypher): DynamicTest {
        val cypherParamsBlock = codeBlocks["[source,json]"]
        return DynamicTest.dynamicTest("Test Cypher Params", cypherParamsBlock?.uri) {
            val resultParams = result().params
            val cypherParams = cypherParamsBlock?.code?.toString()?.parseJsonMap() ?: emptyMap()
            Assertions.assertEquals(fixNumbers(cypherParams), fixNumbers(resultParams)) {
                "\nExpected : ${MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(cypherParams)}\n" +
                        "Actual   :${MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(resultParams)}"
            }
        }
    }

    companion object {
        const val DEBUG = false
    }
}
