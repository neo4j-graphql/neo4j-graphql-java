package org.neo4j.graphql.examples.dgsspringboot.datafetcher

import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import graphql.schema.DataFetchingEnvironment
import org.neo4j.graphql.examples.dgsspringboot.types.DgsConstants
import org.neo4j.graphql.examples.dgsspringboot.types.types.JavaData
import java.util.*


@DgsComponent
class AdditionalDataFetcher {
    @DgsData(parentType = DgsConstants.MOVIE.TYPE_NAME, field = DgsConstants.MOVIE.Bar)
    fun bar(): String {
        return "foo"
    }

    @DgsData(parentType = DgsConstants.MOVIE.TYPE_NAME, field = DgsConstants.MOVIE.JavaData)
    fun javaData(env: DataFetchingEnvironment): List<JavaData> {
        val title = env.getSource<Map<String, *>>()?.get("title")
        return Collections.singletonList(JavaData("test $title"))
    }

    @DgsData(parentType = DgsConstants.QUERY.TYPE_NAME, field = DgsConstants.QUERY.Other)
    fun other(): String {
        return "other"
    }
}
