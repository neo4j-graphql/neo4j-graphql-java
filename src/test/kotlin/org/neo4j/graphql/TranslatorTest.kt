package org.neo4j.graphql

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TranslatorTest(val test: AsciiDocTestSuite.TestRun) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun data(): Collection<AsciiDocTestSuite.TestRun> {
            return AsciiDocTestSuite("translator-tests1.adoc").tests +
                    AsciiDocTestSuite("translator-tests2.adoc").tests +
                    AsciiDocTestSuite("translator-tests3.adoc").tests
        }
    }

    @Test
    fun testTck() {
        test.run();
    }
}