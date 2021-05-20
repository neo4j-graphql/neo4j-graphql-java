package org.neo4j.graphql.examples.dgsspringboot.datafetcher

import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import graphql.schema.DataFetchingEnvironment
import org.neo4j.graphql.examples.dgsspringboot.types.types.JavaData
import java.util.*


@DgsComponent
class AdditionalDataFetcher {
    @DgsData(parentType = "Movie", field = "bar")
    fun bar(): String {
        return "foo"
    }

    @DgsData(parentType = "Movie", field = "javaData")
    fun javaData(env: DataFetchingEnvironment): List<JavaData> {
        val title = env.getSource<Map<String, *>>()["title"]
        return Collections.singletonList(JavaData("test $title"))
    }

    @DgsData(parentType = "Query", field = "other")
    fun other(): String {
        return "other"
    }
}
