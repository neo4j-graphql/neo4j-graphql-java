package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.NodeFacade
import org.neo4j.graphql.RelationshipInfo
import org.neo4j.graphql.Translator
import org.neo4j.graphql.handler.ProjectionRepository
import org.neo4j.graphql.quote

class CreateRelationTypeHandler(
        type: NodeFacade,
        relation: RelationshipInfo,
        startId: RelationshipInfo.RelatedField,
        endId: RelationshipInfo.RelatedField,
        fieldDefinition: FieldDefinition,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        projectionRepository: ProjectionRepository)
    : BaseRelationHandler(type, relation, startId, endId, fieldDefinition, typeDefinitionRegistry, projectionRepository) {


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
                " CREATE (${relation.startField})-[$variable:${relation.relType.quote()}${properties.query}]->(${relation.endField})" +
                " WITH $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                startSelect.params + endSelect.params + properties.params,
                false)
    }
}
