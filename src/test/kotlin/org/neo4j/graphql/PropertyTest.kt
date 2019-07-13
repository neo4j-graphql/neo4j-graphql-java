package org.neo4j.graphql

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PropertyTest(val test: AsciiDocTestSuite.TestRun) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun data(): Collection<AsciiDocTestSuite.TestRun> {
            return AsciiDocTestSuite("property-tests.adoc").tests
        }
    }

    @Test
    fun testTck() {
        test.run();
    }
}