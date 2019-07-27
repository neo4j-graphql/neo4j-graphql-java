package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.language.ObjectTypeDefinition
import org.neo4j.graphql.*

class DeleteRelationHandler private constructor(
        type: NodeFacade,
        relation: RelationshipInfo,
        startId: RelationshipInfo.RelatedField,
        endId: RelationshipInfo.RelatedField,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider)
    : BaseRelationHandler(type, relation, startId, endId, fieldDefinition, metaProvider) {

    companion object {
        fun build(source: ObjectTypeDefinition,
                target: ObjectTypeDefinition,
                metaProvider: MetaProvider): DeleteRelationHandler? {

            return build("delete", source, target, metaProvider) { sourceNodeType, relation, startIdField, endIdField, targetField, fieldDefinitionBuilder ->
                DeleteRelationHandler(sourceNodeType, relation, startIdField, endIdField, fieldDefinitionBuilder.build(), metaProvider)
            }
        }
    }

    override fun generateCypher(
            variable: String,
            field: Field,
            projectionProvider: () -> Cypher,
            ctx: Translator.Context): Cypher {
        val mapProjection = projectionProvider.invoke()
        val arguments = field.arguments.map { it.name to it }.toMap()
        val startSelect = getRelationSelect(true, arguments)
        val endSelect = getRelationSelect(false, arguments)

        return Cypher("MATCH ${startSelect.query}" +
                " MATCH ${endSelect.query}" +
                " MATCH (${relation.startField})-[r:${relation.relType.quote()}]->(${relation.endField})" +
                " DELETE r" +
                " WITH DISTINCT ${relation.startField} AS $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                startSelect.params + endSelect.params,
                false)
    }

}
