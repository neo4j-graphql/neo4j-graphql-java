package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.NodeFacade
import org.neo4j.graphql.Translator
import org.neo4j.graphql.quote

class DeleteHandler(
        type: NodeFacade,
        private val idField: FieldDefinition,
        fieldDefinition: FieldDefinition,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        projectionRepository: ProjectionRepository,
        val isRealtion: Boolean = type.isRelationType()
) : BaseDataFetcher(type, fieldDefinition, typeDefinitionRegistry, projectionRepository) {

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
