package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import org.neo4j.graphql.*

class CreateTypeHandler private constructor(
        type: NodeFacade,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider
) : BaseDataFetcher(type, fieldDefinition, metaProvider) {

    companion object {
        fun build(type: ObjectDefinitionNodeFacade, metaProvider: MetaProvider): CreateTypeHandler? {
            val scalarFields = type.scalarFields()
            if (scalarFields.isEmpty()) {
                return null
            }
            val fieldDefinition = createFieldDefinition("create", type.name(), scalarFields.filter { !it.isNativeId() }).build()
            return CreateTypeHandler(type, fieldDefinition, metaProvider)
        }
    }

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Translator.Cypher, ctx: Translator.Context): Translator.Cypher {
        val properties = properties(variable, field.arguments)
        val mapProjection = projectionProvider.invoke()
        return Translator.Cypher("CREATE ($variable:${allLabels()}${properties.query})" +
                " WITH $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                (mapProjection.params + properties.params), false)
    }

}
