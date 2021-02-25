package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import org.neo4j.graphql.Cypher
import org.neo4j.graphql.aliasOrName
import org.neo4j.graphql.handler.projection.ProjectionBase

abstract class BaseDataFetcher(val fieldDefinition: GraphQLFieldDefinition) : ProjectionBase(), DataFetcher<Cypher> {

    override fun get(env: DataFetchingEnvironment?): Cypher {
        val field = env?.mergedField?.singleField
                ?: throw IllegalAccessException("expect one filed in environment.mergedField")
        require(field.name == fieldDefinition.name) { "Handler for ${fieldDefinition.name} cannot handle ${field.name}" }
        val variable = field.aliasOrName().decapitalize()
        return generateCypher(variable, field, env)
            .copy(type = fieldDefinition.type, variable = variable)
    }

    protected abstract fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Cypher
}
