package org.neo4j.graphql

import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.utils.CypherTestSuite

class CypherTests {

    @TestFactory
    fun `cypher-directive-tests`() = CypherTestSuite("cypher-directive-tests.adoc").run()

    @TestFactory
    fun `dynamic-property-tests`() = CypherTestSuite("dynamic-property-tests.adoc").run()

    @TestFactory
    fun `filter-tests`() = CypherTestSuite("filter-tests.adoc").run()

    @TestFactory
    fun `movie-tests`() = CypherTestSuite("movie-tests.adoc").run()

    @TestFactory
    fun `property-tests`() = CypherTestSuite("property-tests.adoc").run()

    @TestFactory
    fun `translator-tests1`() = CypherTestSuite("translator-tests1.adoc").run()

    @TestFactory
    fun `translator-tests2`() = CypherTestSuite("translator-tests2.adoc").run()

    @TestFactory
    fun `translator-tests3`() = CypherTestSuite("translator-tests3.adoc").run()

    @TestFactory
    fun `object-filter-tests`() = CypherTestSuite("object-filter-tests.adoc").run { ctx ->
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