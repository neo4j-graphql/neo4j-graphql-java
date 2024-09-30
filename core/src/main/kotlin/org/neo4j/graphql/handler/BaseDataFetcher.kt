package org.neo4j.graphql.handler

import graphql.language.VariableReference
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.renderer.Configuration
import org.neo4j.cypherdsl.core.renderer.Dialect
import org.neo4j.cypherdsl.core.renderer.Renderer
import org.neo4j.graphql.CypherDataFetcherResult
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.driver.adapter.Neo4jAdapter
import org.neo4j.graphql.queryContext

/**
 * This is a base class for the implementation of graphql data fetcher used in this project
 */
internal abstract class BaseDataFetcher(protected val schemaConfig: SchemaConfig) :
    DataFetcher<CypherDataFetcherResult> {

    final override fun get(env: DataFetchingEnvironment): CypherDataFetcherResult {
        val variable = "this"
        val statement = generateCypher(variable, env)
        val dialect = when (env.queryContext().neo4jDialect) {
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
        return CypherDataFetcherResult(query, params, env.fieldDefinition.type, variable = variable)
    }

    protected abstract fun generateCypher(variable: String, env: DataFetchingEnvironment): Statement
}
