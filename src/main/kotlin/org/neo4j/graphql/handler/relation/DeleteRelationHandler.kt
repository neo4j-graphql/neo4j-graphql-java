package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.language.FieldDefinition
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.projection.ProjectionRepository

class DeleteRelationHandler(
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
            ctx: Translator.Context): Translator.Cypher {
        val mapProjection = projectionProvider.invoke()
        val arguments = field.arguments.map { it.name to it }.toMap()
        val startSelect = getRelationSelect(true, arguments)
        val endSelect = getRelationSelect(false, arguments)

        return Translator.Cypher("MATCH ${startSelect.query}" +
                " MATCH ${endSelect.query}" +
                " MATCH (${relation.startField})-[r:${relation.relType.quote()}]->(${relation.endField})" +
                " DELETE r" +
                " WITH DISTINCT ${relation.startField} AS $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                startSelect.params + endSelect.params ,
                false)
    }

}
