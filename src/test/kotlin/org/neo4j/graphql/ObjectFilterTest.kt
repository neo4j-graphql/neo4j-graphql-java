package org.neo4j.graphql

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ObjectFilterTest(val test: AsciiDocTestSuite.TestRun) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun data(): Collection<AsciiDocTestSuite.TestRun> {
            return AsciiDocTestSuite("object-filter-tests.adoc").tests
        }
    }

    @Test
    fun testTck() {
        test.run { ctx ->
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
}
