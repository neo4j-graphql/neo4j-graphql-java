package org.neo4j.graphql.examples.graphqlspringboot.controller

import com.expediagroup.graphql.spring.operations.Query
import org.springframework.stereotype.Component

@Component
class AdditionalQueries : Query {

    fun echo(string: String): String {
        return string
    }

    fun pojo(param: String): Pojo {
        return Pojo(param)
    }

    data class Pojo(val id: String)
}
