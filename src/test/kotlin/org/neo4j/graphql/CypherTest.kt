package org.neo4j.graphql

import org.junit.jupiter.api.TestFactory

class CypherTest {

    @TestFactory
    fun `cypher-directive-tests`() = AsciiDocTestSuite("cypher-directive-tests.adoc").run()

    @TestFactory
    fun `dynamic-property-tests`() = AsciiDocTestSuite("dynamic-property-tests.adoc").run()

    @TestFactory
    fun `filter-tests`() = AsciiDocTestSuite("filter-tests.adoc").run()

    @TestFactory
    fun `movie-tests`() = AsciiDocTestSuite("movie-tests.adoc").run()

    @TestFactory
    fun `property-tests`() = AsciiDocTestSuite("property-tests.adoc").run()

    @TestFactory
    fun `translator-tests1`() = AsciiDocTestSuite("translator-tests1.adoc").run()

    @TestFactory
    fun `translator-tests2`() = AsciiDocTestSuite("translator-tests2.adoc").run()

    @TestFactory
    fun `translator-tests3`() = AsciiDocTestSuite("translator-tests3.adoc").run()

    @TestFactory
    fun `object-filter-tests`() = AsciiDocTestSuite("object-filter-tests.adoc").run { ctx ->
        // tag::example[]
        val objectFilterProvider: (variable: String, type: NodeFacade) -> Translator.Cypher? = { variable, type ->
            (type.getDirective("filter")
                ?.getArgument("statement")
                ?.value?.toJavaValue() as? String)
                ?.replace("this", variable)
                ?.let { query ->
                    // extract all variables $...
                    val params = "\\\$(\\w+)".toRegex().findAll(query).map { it.groups.get(1)?.value }
                        .filterNotNull()
                        .map { ctxVar -> ctxVar to ctx.params[ctxVar] }
                        .toMap()
                    Translator.Cypher(query, params)
                }
        }
        // end::example[]
        ctx.copy(objectFilterProvider = objectFilterProvider)
    }

}