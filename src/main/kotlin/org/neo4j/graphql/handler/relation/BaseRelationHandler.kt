package org.neo4j.graphql.handler.relation

import graphql.language.FieldDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.NodeDefinitionFacade
import org.neo4j.graphql.RelationshipInfo
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.handler.ProjectionRepository

abstract class BaseRelationHandler(
        type: NodeDefinitionFacade,
        val relation: RelationshipInfo,
        val startId: RelationshipInfo.RelatedField,
        val endId: RelationshipInfo.RelatedField,
        fieldDefinition: FieldDefinition,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        projectionRepository: ProjectionRepository
) : BaseDataFetcher(type, fieldDefinition, typeDefinitionRegistry, projectionRepository) {

    init {
        propertyFields.remove(relation.startField)
        propertyFields.remove(relation.endField)
    }


}