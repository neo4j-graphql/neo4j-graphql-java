package org.neo4j.graphql.handler.relation

import graphql.language.Argument
import graphql.language.FieldDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.NodeFacade
import org.neo4j.graphql.RelationshipInfo
import org.neo4j.graphql.Translator
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.handler.ProjectionRepository
import org.neo4j.graphql.quote

abstract class BaseRelationHandler(
        type: NodeFacade,
        val relation: RelationshipInfo,
        val startId: RelationshipInfo.RelatedField,
        val endId: RelationshipInfo.RelatedField,
        fieldDefinition: FieldDefinition,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        projectionRepository: ProjectionRepository)
    : BaseDataFetcher(type, fieldDefinition, typeDefinitionRegistry, projectionRepository) {

    init {
        propertyFields.remove(startId.argumentName)
        propertyFields.remove(endId.argumentName)
    }

    fun getRelationSelect(start: Boolean, arguments: Map<String, Argument>): Translator.Cypher {
        val relFieldName: String
        val idField: RelationshipInfo.RelatedField
        if (start) {
            relFieldName = relation.startField!!
            idField = startId
        } else {
            relFieldName = relation.endField!!
            idField = endId
        }
        if (!arguments.containsKey(idField.argumentName)) {
            throw java.lang.IllegalArgumentException("No ID for the ${if (start) "start" else "end"} Type provided, ${idField.argumentName} is required")
        }
        return getSelectQuery(relFieldName, idField.declaringType.label(), arguments[idField.argumentName],
                idField.field, false, (relFieldName + idField.argumentName.capitalize()).quote())
    }

}