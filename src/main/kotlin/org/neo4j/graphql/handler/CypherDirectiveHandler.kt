package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.NodeFacade
import org.neo4j.graphql.Translator

class CypherDirectiveHandler(
        type: NodeFacade,
        val isQuery: Boolean,
        val cypherDirective: Translator.Cypher,
        fieldDefinition: FieldDefinition,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        projectionRepository: ProjectionRepository)
    : BaseDataFetcher(type, fieldDefinition, typeDefinitionRegistry, projectionRepository) {

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Translator.Cypher, ctx: Translator.Context): Translator.Cypher {
        val mapProjection = projectionProvider.invoke()
        val ordering = orderBy(variable, field.arguments)
        val skipLimit = ProjectionHandler.SkipLimit(variable, field.arguments).format()

        return if (isQuery) {
            val (query, params) = cypherDirective(variable, fieldDefinition, field, cypherDirective, emptyList())
            Translator.Cypher("UNWIND $query AS $variable" +
                    " RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}",
                    (params + mapProjection.params + skipLimit.params))
        } else {
            val (query, params) = cypherDirectiveQuery(variable, fieldDefinition, field, cypherDirective, emptyList())
            Translator.Cypher("CALL apoc.cypher.doIt($query) YIELD value" +
                    " WITH value[head(keys(value))] AS $variable" +
                    " RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}",
                    (params + mapProjection.params + skipLimit.params))
        }
    }
}