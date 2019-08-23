package org.neo4j.graphql.handler

import graphql.language.Description
import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.language.TypeName
import graphql.schema.DataFetchingEnvironment
import org.neo4j.graphql.Cypher
import org.neo4j.graphql.MetaProvider
import org.neo4j.graphql.NodeFacade
import org.neo4j.graphql.isID

class DeleteHandler private constructor(
        type: NodeFacade,
        private val idField: FieldDefinition,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider
) : BaseDataFetcher(type, fieldDefinition, metaProvider) {

    companion object {
        fun build(type: NodeFacade, metaProvider: MetaProvider): DeleteHandler? {
            val idField = type.fieldDefinitions().find { it.isID() } ?: return null
            val relevantFields = type.relevantFields()
            if (relevantFields.isEmpty()) {
                return null
            }
            val typeName = type.name()

            val fieldDefinition = createFieldDefinition("delete", typeName, listOf(idField), true)
                .description(Description("Deletes $typeName and returns its ID on successful deletion", null, false))
                .type(TypeName(typeName))
                .build()
            return DeleteHandler(type, idField, fieldDefinition, metaProvider)
        }
    }

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Cypher, env: DataFetchingEnvironment): Cypher {
        val idArg = field.arguments.first { it.name == idField.name }
        val mapProjection = projectionProvider.invoke()

        val select = getSelectQuery(variable, label(), idArg, idField)
        return Cypher("MATCH " + select.query +
                " WITH $variable as toDelete, " +
                "${mapProjection.query} AS $variable" +
                " DETACH DELETE toDelete" +
                " RETURN $variable",
                select.params + mapProjection.params)
    }

}
