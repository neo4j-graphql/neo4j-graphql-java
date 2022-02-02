package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.VariableReference
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLType
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.renderer.Configuration
import org.neo4j.cypherdsl.core.renderer.Renderer
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.projection.ProjectionBase

/**
 * This is a base class for the implementation of graphql data fetcher used in this project
 */
abstract class BaseDataFetcher(schemaConfig: SchemaConfig) : ProjectionBase(schemaConfig), DataFetcher<Cypher> {

    private var init = false

    override fun get(env: DataFetchingEnvironment): Cypher {
        val field = env.mergedField?.singleField
                ?: throw IllegalAccessException("expect one filed in environment.mergedField")
        val variable = field.aliasOrName().decapitalize()
        prepareDataFetcher(env.fieldDefinition, env.parentType)
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
        return Cypher(query, params, env.fieldDefinition.type, variable = field.aliasOrName())
            .also {
                (env.getLocalContext() as? Translator.CypherHolder)?.apply { this.cyphers += it }
            }
    }

    /**
     * called after the schema is generated but before the 1st call
     */
    private fun prepareDataFetcher(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType) {
        if (init) {
            return
        }
        init = true
        initDataFetcher(fieldDefinition, parentType)
    }

    protected open fun initDataFetcher(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType) {
    }

    protected abstract fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement
}
