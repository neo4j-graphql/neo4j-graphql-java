package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.schema.DataFetchingEnvironment
import org.neo4j.graphql.Cypher
import org.neo4j.graphql.MetaProvider
import org.neo4j.graphql.NodeFacade
import org.neo4j.graphql.isID

class MergeOrUpdateHandler private constructor(
        type: NodeFacade,
        private val merge: Boolean,
        private val idField: FieldDefinition,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider
) : BaseDataFetcher(type, fieldDefinition, metaProvider) {

    companion object {
        fun build(type: NodeFacade, merge: Boolean, metaProvider: MetaProvider): MergeOrUpdateHandler? {
            val idField = type.fieldDefinitions().find { it.isID() } ?: return null
            val relevantFields = type.relevantFields()
            if (relevantFields.none { !it.isID() }) {
                // nothing to update (except ID)
                return null
            }
            val fieldDefinition = createFieldDefinition(if (merge) "merge" else "update", type.name(), relevantFields, !merge).build()
            return MergeOrUpdateHandler(type, merge, idField, fieldDefinition, metaProvider)
        }
    }

    init {
        defaultFields.clear()
        propertyFields.remove(idField.name) // id should not be updated
    }

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Cypher, env: DataFetchingEnvironment): Cypher {
        val idArg = field.arguments.first { it.name == idField.name }

        val properties = properties(variable, field.arguments)
        val mapProjection = projectionProvider.invoke()

        val op = if (merge) "+" else ""
        val select = getSelectQuery(variable, label(), idArg, idField)
        return Cypher((if (merge) "MERGE " else "MATCH ") + select.query +
                " SET $variable $op= " + properties.query +
                " WITH $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                select.params + properties.params + mapProjection.params)
    }
}
