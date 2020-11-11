package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import org.neo4j.graphql.*

class CypherDirectiveHandler(
        private val type: GraphQLFieldsContainer?,
        private val isQuery: Boolean,
        private val cypherDirective: Cypher,
        fieldDefinition: GraphQLFieldDefinition)
    : BaseDataFetcher(fieldDefinition) {

    class Factory(schemaConfig: SchemaConfig) : AugmentationHandler(schemaConfig) {

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            val cypherDirective = fieldDefinition.cypherDirective() ?: return null
            val type = fieldDefinition.type.inner() as? GraphQLFieldsContainer
            val isQuery = operationType == OperationType.QUERY
            return CypherDirectiveHandler(type, isQuery, cypherDirective, fieldDefinition)
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Cypher {
        val mapProjection = type?.let { projectFields(variable, field, it, env, null) }
                ?: Cypher(variable)
        val ordering = orderBy(variable, field.arguments)
        val skipLimit = SkipLimit(variable, field.arguments).format()

        return if (isQuery) {
            val (query, params) = cypherDirective(variable, fieldDefinition, field, cypherDirective, emptyList())
            Cypher("UNWIND $query AS $variable" +
                    " RETURN ${mapProjection.query} AS ${field.aliasOrName()}$ordering${skipLimit.query}",
                    (params + mapProjection.params + skipLimit.params))
        } else {
            val (query, params) = cypherDirectiveQuery(variable, fieldDefinition, field, cypherDirective, emptyList())
            Cypher("CALL apoc.cypher.doIt($query) YIELD value" +
                    " WITH value[head(keys(value))] AS $variable" +
                    " RETURN ${mapProjection.query} AS ${field.aliasOrName()}$ordering${skipLimit.query}",
                    (params + mapProjection.params + skipLimit.params))
        }
    }
}
