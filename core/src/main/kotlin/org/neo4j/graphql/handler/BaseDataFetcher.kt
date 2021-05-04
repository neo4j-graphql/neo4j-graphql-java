package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.VariableReference
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.renderer.Configuration
import org.neo4j.cypherdsl.core.renderer.Renderer
import org.neo4j.graphql.Cypher
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.aliasOrName
import org.neo4j.graphql.handler.projection.ProjectionBase

/**
 * The is a base class for the implementation of graphql data fetcher used in this project
 */
abstract class BaseDataFetcher(val fieldDefinition: GraphQLFieldDefinition, schemaConfig: SchemaConfig) : ProjectionBase(schemaConfig), DataFetcher<Cypher> {

    override fun get(env: DataFetchingEnvironment?): Cypher {
        val field = env?.mergedField?.singleField
                ?: throw IllegalAccessException("expect one filed in environment.mergedField")
        require(field.name == fieldDefinition.name) { "Handler for ${fieldDefinition.name} cannot handle ${field.name}" }
        val variable = field.aliasOrName().decapitalize()
        val statement = generateCypher(variable, field, env)

        val query = Renderer.getRenderer(Configuration
            .newConfig()
            .withIndentStyle(Configuration.IndentStyle.TAB)
            .withPrettyPrint(true)
            .build()
        ).render(statement)

        val params = statement.parameters.mapValues { (_, value) ->
            (value as? VariableReference)?.let { env.variables[it.name] } ?: value
        }

        return Cypher(query, params, fieldDefinition.type, variable = field.aliasOrName())
    }

    protected abstract fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement
}
