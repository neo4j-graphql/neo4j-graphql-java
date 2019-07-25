package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.NodeDefinitionFacade
import org.neo4j.graphql.Translator

class CreateTypeHandler(
        type: NodeDefinitionFacade,
        fieldDefinition: FieldDefinition,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        projectionRepository: ProjectionRepository
) : BaseDataFetcher(type, fieldDefinition, typeDefinitionRegistry, projectionRepository) {

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Translator.Cypher, ctx: Translator.Context): Translator.Cypher {
        val properties = properties(variable, field.arguments)
        val mapProjection = projectionProvider.invoke()
        return Translator.Cypher("CREATE ($variable:${allLabels()}${properties.query})" +
                " WITH $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                (mapProjection.params + properties.params), false)
    }

}
