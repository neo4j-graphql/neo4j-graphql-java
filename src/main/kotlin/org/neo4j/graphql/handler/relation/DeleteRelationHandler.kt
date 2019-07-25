package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.NodeDefinitionFacade
import org.neo4j.graphql.Translator
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.handler.ProjectionRepository

class DeleteRelationHandler(
        nodeType: NodeDefinitionFacade,
        sourceIdField: FieldDefinition,
        fieldDefinition: FieldDefinition,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        projectionRepository: ProjectionRepository)
    : BaseDataFetcher(nodeType, fieldDefinition, typeDefinitionRegistry, projectionRepository) {

    override fun generateCypher(
            variable: String,
            field: Field,
            projectionProvider: () -> Translator.Cypher,
            ctx: Translator.Context): Translator.Cypher {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

}
