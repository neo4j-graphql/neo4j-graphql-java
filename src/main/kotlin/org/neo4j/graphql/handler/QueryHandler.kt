package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import org.neo4j.graphql.MetaProvider
import org.neo4j.graphql.NodeFacade
import org.neo4j.graphql.Translator
import org.neo4j.graphql.handler.projection.ProjectionRepository
import org.neo4j.graphql.isList

class QueryHandler(
        type: NodeFacade,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider,
        projectionRepository: ProjectionRepository)
    : BaseDataFetcher(type, fieldDefinition, metaProvider, projectionRepository) {

    private val isList = fieldDefinition.type.isList()

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Translator.Cypher, ctx: Translator.Context): Translator.Cypher {

        val mapProjection = projectionProvider.invoke()
        val ordering = orderBy(variable, field.arguments)
        val skipLimit = SkipLimit(variable, field.arguments).format()

        val select = if (type.isRelationType()) {
            "()-[$variable:${label()}]->()"
        } else {
            "($variable:${label()})"
        }
        val where = where(variable, fieldDefinition, type, propertyArguments(field), ctx)
        return Translator.Cypher("MATCH $select${where.query}" +
                " RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}",
                (where.params + mapProjection.params + skipLimit.params),
                isList)
    }
}