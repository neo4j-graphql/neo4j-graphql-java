package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.language.FieldDefinition
import org.neo4j.graphql.*

class CreateRelationTypeHandler private constructor(
        type: NodeFacade,
        relation: RelationshipInfo,
        startId: RelationshipInfo.RelatedField,
        endId: RelationshipInfo.RelatedField,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider)
    : BaseRelationHandler(type, relation, startId, endId, fieldDefinition, metaProvider) {

    companion object {
        fun build(type: ObjectDefinitionNodeFacade, metaProvider: MetaProvider): CreateRelationTypeHandler? {
            val scalarFields = type.scalarFields()
            if (scalarFields.isEmpty()) {
                return null
            }
            val relation = type.relationship(metaProvider)!!
            val startIdField = relation.getStartFieldId(metaProvider)
            val endIdField = relation.getEndFieldId(metaProvider)
            if (startIdField == null || endIdField == null) {
                return null
            }
            val createArgs = scalarFields
                .filter { !it.isNativeId() }
                .filter { it.name != startIdField.argumentName }
                .filter { it.name != endIdField.argumentName }

            val builder = createFieldDefinition("create", type.name(), emptyList())
                .inputValueDefinition(input(startIdField.argumentName, startIdField.field.type))
                .inputValueDefinition(input(endIdField.argumentName, endIdField.field.type))
            createArgs.forEach { builder.inputValueDefinition(input(it.name, it.type)) }

            return CreateRelationTypeHandler(type, relation, startIdField, endIdField, builder.build(), metaProvider)
        }
    }

    override fun generateCypher(
            variable: String,
            field: Field,
            projectionProvider: () -> Cypher,
            ctx: Translator.Context
    ): Cypher {
        val properties = properties(variable, field.arguments)
        val mapProjection = projectionProvider.invoke()

        val arguments = field.arguments.map { it.name to it }.toMap()
        val startSelect = getRelationSelect(true, arguments)
        val endSelect = getRelationSelect(false, arguments)

        return Cypher("MATCH ${startSelect.query}" +
                " MATCH ${endSelect.query}" +
                " CREATE (${relation.startField})-[$variable:${relation.relType.quote()}${properties.query}]->(${relation.endField})" +
                " WITH $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                startSelect.params + endSelect.params + properties.params,
                false)
    }
}
