package org.neo4j.graphql.handler

import graphql.language.Description
import graphql.language.Field
import graphql.language.FieldDefinition
import org.neo4j.graphql.*

class DeleteHandler private constructor(
        type: NodeFacade,
        private val idField: FieldDefinition,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider,
        private val isRealtion: Boolean = type.isRelationType()
) : BaseDataFetcher(type, fieldDefinition, metaProvider) {

    companion object {
        fun build(type: NodeFacade, metaProvider: MetaProvider): DeleteHandler? {
            val idField = type.fieldDefinitions().find { it.isID() } ?: return null
            val scalarFields = type.scalarFields()
            if (scalarFields.isEmpty()) {
                return null
            }
            val typeName = type.name()

            val fieldDefinition = createFieldDefinition("delete", typeName, listOf(idField))
                .description(Description("Deletes $typeName and returns its ID on successful deletion", null, false))
                .type(idField.type.inner())
                .build()
            return DeleteHandler(type, idField, fieldDefinition, metaProvider)
        }
    }

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Translator.Cypher, ctx: Translator.Context): Translator.Cypher {
        val idArg = field.arguments.first { it.name == idField.name }

        val select = getSelectQuery(variable, label(), idArg, idField, isRealtion)
        return Translator.Cypher("MATCH " + select.query +
                " WITH $variable as toDelete" +
                " DETACH DELETE toDelete" +
                " RETURN {${idArg.name.quote()}} AS $variable",
                select.params,
                false)
    }

}
