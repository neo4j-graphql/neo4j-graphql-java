package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.schema.*
import org.neo4j.graphql.*

class CypherDirectiveHandler(
        type: GraphQLFieldsContainer,
        private val isQuery: Boolean,
        private val cypherDirective: Cypher,
        fieldDefinition: GraphQLFieldDefinition)
    : BaseDataFetcher(type, fieldDefinition) {

    class Factory(schemaConfig: SchemaConfig) : AugmentationHandler(schemaConfig) {

        override fun createDataFetcher(rootType: GraphQLObjectType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            val cypherDirective = fieldDefinition.cypherDirective() ?: return null
            // TODO cypher directives can also return scalars
            val type = fieldDefinition.type.inner() as? GraphQLFieldsContainer
                    ?: return null
            val isQuery = rootType.name == QUERY
            return CypherDirectiveHandler(type, isQuery, cypherDirective, fieldDefinition)
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Cypher {
        val mapProjection = projectFields(variable, field, type, env, null)
        val ordering = orderBy(variable, field.arguments)
        val skipLimit = SkipLimit(variable, field.arguments).format()

        return if (isQuery) {
            val (query, params) = cypherDirective(variable, fieldDefinition, field, cypherDirective, emptyList())
            Cypher("UNWIND $query AS $variable" +
                    " RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}",
                    (params + mapProjection.params + skipLimit.params))
        } else {
            val (query, params) = cypherDirectiveQuery(variable, fieldDefinition, field, cypherDirective, emptyList())
            Cypher("CALL apoc.cypher.doIt($query) YIELD value" +
                    " WITH value[head(keys(value))] AS $variable" +
                    " RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}",
                    (params + mapProjection.params + skipLimit.params))
        }
    }
}