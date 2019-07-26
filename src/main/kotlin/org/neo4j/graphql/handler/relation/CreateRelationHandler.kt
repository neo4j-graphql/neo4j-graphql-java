package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.language.FieldDefinition
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.projection.ProjectionRepository

class CreateRelationHandler(
        type: NodeFacade,
        relation: RelationshipInfo,
        startId: RelationshipInfo.RelatedField,
        endId: RelationshipInfo.RelatedField,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider,
        projectionRepository: ProjectionRepository)
    : BaseRelationHandler(type, relation, startId, endId, fieldDefinition, metaProvider, projectionRepository) {

    override fun generateCypher(
            variable: String,
            field: Field,
            projectionProvider: () -> Translator.Cypher,
            ctx: Translator.Context
    ): Translator.Cypher {

        val properties = properties(variable, field.arguments)
        val mapProjection = projectionProvider.invoke()

        val arguments = field.arguments.map { it.name to it }.toMap()
        val startSelect = getRelationSelect(true, arguments)
        val endSelect = getRelationSelect(false, arguments)

        return Translator.Cypher("MATCH ${startSelect.query}" +
                " MATCH ${endSelect.query}" +
                " MERGE (${relation.startField})-[:${relation.relType.quote()}${properties.query}]->(${relation.endField})" +
                " WITH DISTINCT ${relation.startField} AS $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                startSelect.params + endSelect.params + properties.params,
                false)
    }
}
