package org.neo4j.graphql.handler.relation

import graphql.language.Argument
import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.ProjectionRepository

class CreateRelationHandler(
        type: NodeDefinitionFacade,
        relation: RelationshipInfo,
        startId: RelationshipInfo.RelatedField,
        endId: RelationshipInfo.RelatedField,
        fieldDefinition: FieldDefinition,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        projectionRepository: ProjectionRepository)
    : BaseRelationHandler(type, relation, startId, endId, fieldDefinition, typeDefinitionRegistry, projectionRepository) {


    override fun generateCypher(
            variable: String,
            field: Field,
            projectionProvider: () -> Translator.Cypher,
            ctx: Translator.Context
    ): Translator.Cypher {
        val properties = properties(variable, field.arguments)
        val mapProjection = projectionProvider.invoke()

        val arguments = field.arguments.map { it.name to it }.toMap()
        val startSelect = getRelationSelect(true, arguments)
        val endSelect = getRelationSelect(false, arguments)

        return Translator.Cypher("MATCH ${startSelect.query}" +
                " MATCH ${endSelect.query}" +
                " CREATE (${relation.startField})-[$variable:${relation.relType.quote()} ${properties.query}]->(${relation.endField})" +
                " WITH $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                startSelect.params + endSelect.params + properties.params,
                false)
    }

    private fun getRelationSelect(start: Boolean, arguments: Map<String, Argument>): Translator.Cypher {
        val relFieldName: String
        val idField: RelationshipInfo.RelatedField
        if (start) {
            relFieldName = relation.startField!!
            idField = startId
        } else {
            relFieldName = relation.endField!!
            idField = endId
        }
        if (!arguments.containsKey(idField.argumentName)) {
            throw java.lang.IllegalArgumentException("No ID for the ${if (start) "start" else "end"} Type provided, ${idField.argumentName} is required")
        }
        return getSelectQuery(relFieldName, idField.declaringType.label(), arguments[idField.argumentName],
                idField.field.isNativeId(), false, (relFieldName + idField.argumentName.capitalize()).quote())
    }

}
