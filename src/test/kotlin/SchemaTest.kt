package demo

import graphql.GraphQL
import graphql.schema.idl.RuntimeWiring.newRuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.test.assertEquals

fun main() {
    val schema = """type Query {
                        hello(what:String = "World"): String
                    }"""

    val schemaParser = SchemaParser()
    val typeDefinitionRegistry = schemaParser.parse(schema)

    val runtimeWiring = newRuntimeWiring()
        .type("Query")
        { it.dataFetcher("hello") { env -> "Hello ${env.getArgument<Any>("what")}!" } }
        .build()

    val schemaGenerator = SchemaGenerator()
    val graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)

    val build = GraphQL.newGraphQL(graphQLSchema).build()
    val executionResult = build.execute("""{ hello (what:"Kotlin") } """)

    val data = executionResult.getData<Any>()
    println(data)
    assertEquals(mapOf("hello" to "Hello Kotlin!"), data)
    // Prints: {hello=Hello Kotlin!}
}

