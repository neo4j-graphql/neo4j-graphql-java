package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import org.neo4j.graphql.*

class MergeOrUpdateHandler private constructor(
        type: NodeFacade,
        private val merge: Boolean,
        private val idField: FieldDefinition,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider,
        private val isRealtion: Boolean = type.isRelationType()
) : BaseDataFetcher(type, fieldDefinition, metaProvider) {

    companion object {
        fun build(type: NodeFacade, merge: Boolean, metaProvider: MetaProvider): MergeOrUpdateHandler? {
            val idField = type.fieldDefinitions().find { it.isID() } ?: return null
            val scalarFields = type.scalarFields()
            if (scalarFields.none { !it.isID() }) {
                // nothing to update (except ID)
                return null
            }
            val fieldDefinition = createFieldDefinition(if (merge) "merge" else "update", type.name(), scalarFields).build()
            return MergeOrUpdateHandler(type, merge, idField, fieldDefinition, metaProvider)
        }
    }

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
