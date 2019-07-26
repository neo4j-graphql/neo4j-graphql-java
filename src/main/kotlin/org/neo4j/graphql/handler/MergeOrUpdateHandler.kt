package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import org.neo4j.graphql.MetaProvider
import org.neo4j.graphql.NodeFacade
import org.neo4j.graphql.Translator
import org.neo4j.graphql.handler.projection.ProjectionRepository
import org.neo4j.graphql.isNativeId

class MergeOrUpdateHandler(
        type: NodeFacade,
        val merge: Boolean,
        val idField: FieldDefinition,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider,
        projectionRepository: ProjectionRepository,
        val isRealtion: Boolean = type.isRelationType()
) : BaseDataFetcher(type, fieldDefinition, metaProvider, projectionRepository) {

    init {
        defaultFields.clear()
        propertyFields.remove(idField.name) // id should not be updated
    }

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Translator.Cypher, ctx: Translator.Context): Translator.Cypher {
        val idArg = field.arguments.first { it.name == idField.name }

        val properties = properties(variable, field.arguments)
        val mapProjection = projectionProvider.invoke()

        val op = if (merge) "+" else ""
        val select = getSelectQuery(variable, label(), idArg, idField, isRealtion)
        return Translator.Cypher((if (merge && !idField.isNativeId()) "MERGE " else "MATCH ") + select.query +
                " SET $variable $op= " + properties.query +
                " WITH $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                select.params + properties.params + mapProjection.params,
                false)
    }
}
