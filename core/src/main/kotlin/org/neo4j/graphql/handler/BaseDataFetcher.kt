package org.neo4j.graphql.handler

import graphql.language.VariableReference
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.renderer.Configuration
import org.neo4j.cypherdsl.core.renderer.Dialect
import org.neo4j.cypherdsl.core.renderer.Renderer
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.driver.adapter.Neo4jAdapter
import org.neo4j.graphql.driver.adapter.Neo4jAdapter.QueryResult
import org.neo4j.graphql.isList

/**
 * This is a base class for the implementation of graphql data fetcher used in this project
 */
internal abstract class BaseDataFetcher(protected val schemaConfig: SchemaConfig) :
    DataFetcher<Any> {

    final override fun get(env: DataFetchingEnvironment): Any {
        val statement = generateCypher(env)
        val neo4jAdapter = env.graphQlContext.get<Neo4jAdapter?>(Neo4jAdapter.CONTEXT_KEY)
        val dialect = when (neo4jAdapter.getDialect()) {
            Neo4jAdapter.Dialect.NEO4J_4 -> Dialect.NEO4J_4
            Neo4jAdapter.Dialect.NEO4J_5 -> Dialect.NEO4J_5
            Neo4jAdapter.Dialect.NEO4J_5_23 -> Dialect.NEO4J_5_23
        }
        val query = Renderer.getRenderer(
            Configuration
                .newConfig()
                .withIndentStyle(Configuration.IndentStyle.TAB)
                .withPrettyPrint(true)
                .withDialect(dialect)
                .build()
        ).render(statement)

        val params = statement.catalog.parameters.mapValues { (_, value) ->
            (value as? VariableReference)?.let { env.variables[it.name] } ?: value
        }

        val result = neo4jAdapter.executeQuery(query, params)
        return mapResult(env, result)
    }

    protected abstract fun generateCypher(env: DataFetchingEnvironment): Statement

    open protected fun mapResult(env: DataFetchingEnvironment, result: QueryResult): Any {
        return if (env.fieldDefinition.type?.isList() == true) {
            result.data.map { it[RESULT_VARIABLE] }
        } else {
            result.data.map { it[RESULT_VARIABLE] }
                .firstOrNull() ?: emptyMap<String, Any>()
        }
    }

    companion object {
        const val RESULT_VARIABLE = "this"
    }
}
