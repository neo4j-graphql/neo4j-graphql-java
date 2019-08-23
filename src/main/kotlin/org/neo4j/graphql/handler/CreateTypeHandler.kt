package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.schema.DataFetchingEnvironment
import org.neo4j.graphql.Cypher
import org.neo4j.graphql.MetaProvider
import org.neo4j.graphql.NodeFacade
import org.neo4j.graphql.ObjectDefinitionNodeFacade

class CreateTypeHandler private constructor(
        type: NodeFacade,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider
) : BaseDataFetcher(type, fieldDefinition, metaProvider) {

    companion object {
        fun build(type: ObjectDefinitionNodeFacade, metaProvider: MetaProvider): CreateTypeHandler? {
            val relevantFields = type.relevantFields()
            if (relevantFields.isEmpty()) {
                return null
            }
            val fieldDefinition = createFieldDefinition("create", type.name(), relevantFields, false).build()
            return CreateTypeHandler(type, fieldDefinition, metaProvider)
        }
    }

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Cypher, env: DataFetchingEnvironment): Cypher {
        val properties = properties(variable, field.arguments)
        val mapProjection = projectionProvider.invoke()
        return Cypher("CREATE ($variable:${allLabels()}${properties.query})" +
                " WITH $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                (mapProjection.params + properties.params))
    }

}
