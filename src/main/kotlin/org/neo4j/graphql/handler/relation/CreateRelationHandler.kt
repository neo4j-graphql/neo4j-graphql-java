package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.language.ObjectTypeDefinition
import org.neo4j.graphql.*

class CreateRelationHandler private constructor(
        type: NodeFacade,
        relation: RelationshipInfo,
        startId: RelationshipInfo.RelatedField,
        endId: RelationshipInfo.RelatedField,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider)
    : BaseRelationHandler(type, relation, startId, endId, fieldDefinition, metaProvider) {

    companion object {
        fun build(source: ObjectTypeDefinition,
                target: ObjectTypeDefinition,
                relationTypes: Map<String, ObjectTypeDefinition>?,
                metaProvider: MetaProvider): CreateRelationHandler? {

            return build("add", source, target, metaProvider) { sourceNodeType, relation, startIdField, endIdField, targetField, fieldDefinitionBuilder ->

                val relationType = targetField
                    .getDirective(DirectiveConstants.RELATION)
                    ?.getArgument(DirectiveConstants.RELATION_NAME)
                    ?.value?.toJavaValue()?.toString()
                    .let { relationTypes?.get(it) }

                relationType
                    ?.fieldDefinitions
                    ?.filter { it.type.isScalar() && !it.isID() }
                    ?.forEach { fieldDefinitionBuilder.inputValueDefinition(input(it.name, it.type)) }

                CreateRelationHandler(sourceNodeType, relation, startIdField, endIdField, fieldDefinitionBuilder.build(), metaProvider)
            }
        }
    }

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
                " MERGE (${relation.startField})-[:${relation.relType.quote()}${properties.query}]->(${relation.endField})" +
                " WITH DISTINCT ${relation.startField} AS $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                startSelect.params + endSelect.params + properties.params,
                false)
    }
}
