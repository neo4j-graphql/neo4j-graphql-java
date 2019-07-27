package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import org.neo4j.graphql.Cypher
import org.neo4j.graphql.MetaProvider
import org.neo4j.graphql.NodeFacade
import org.neo4j.graphql.Translator

class CypherDirectiveHandler(
        type: NodeFacade,
        private val isQuery: Boolean,
        private val cypherDirective: Cypher,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider)
    : BaseDataFetcher(type, fieldDefinition, metaProvider) {

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Cypher, ctx: Translator.Context): Cypher {
        val mapProjection = projectionProvider.invoke()
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