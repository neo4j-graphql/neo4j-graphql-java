package demo.org.neo4j.graphql

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.neo4j.graphql.AsciiDocTestSuite

@RunWith(Parameterized::class)
class MovieSchemaTest(val test: AsciiDocTestSuite.TestRun) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun data(): Collection<AsciiDocTestSuite.TestRun> {
            return AsciiDocTestSuite("movie-tests.adoc").tests
        }
    }

    @Test
    fun testTck() {
        test.run();
    }
}