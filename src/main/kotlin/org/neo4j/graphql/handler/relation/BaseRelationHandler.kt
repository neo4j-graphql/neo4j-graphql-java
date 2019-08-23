package org.neo4j.graphql.handler.relation

import graphql.language.*
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.BaseDataFetcher

abstract class BaseRelationHandler(
        type: NodeFacade,
        val relation: RelationshipInfo,
        private val startId: RelationshipInfo.RelatedField,
        private val endId: RelationshipInfo.RelatedField,
        fieldDefinition: FieldDefinition,
        metaProvider: MetaProvider)
    : BaseDataFetcher(type, fieldDefinition, metaProvider) {

    init {
        propertyFields.remove(startId.argumentName)
        propertyFields.remove(endId.argumentName)
    }

    companion object {
        @JvmStatic
        protected fun <T : BaseDataFetcher> build(prefix: String, source: ObjectTypeDefinition,
                target: ObjectTypeDefinition,
                metaProvider: MetaProvider,
                nullableResult: Boolean,
                handlerFactory: (sourceNodeType: NodeFacade,
                        relation: RelationshipInfo,
                        startIdField: RelationshipInfo.RelatedField,
                        endIdField: RelationshipInfo.RelatedField,
                        targetField: FieldDefinition,
                        fieldDefinitionBuilder: FieldDefinition.Builder) -> T): T? {

            val sourceTypeName = source.name
            val targetField = source.getFieldByType(target.name) ?: return null

            val sourceIdField = source.fieldDefinitions.find { it.isID() }
            val targetIdField = target.fieldDefinitions.find { it.isID() }
            if (sourceIdField == null || targetIdField == null) {
                return null
            }

            val targetFieldName = targetField.name.capitalize()
            val idType = NonNullType(TypeName("ID"))
            val targetIDType = if (targetField.isList()) NonNullType(ListType(idType)) else idType

            val sourceNodeType = source.getNodeType()!!
            val targetNodeType = target.getNodeType()!!
            val relation = sourceNodeType.relationshipFor(targetField.name, metaProvider)
                    ?: throw IllegalArgumentException("could not resolve relation for " + source.name + "::" + targetField.name)

            var type: Type<*> = TypeName(sourceTypeName)
            if (!nullableResult) {
                type = NonNullType(type)
            }

            val fieldDefinitionBuilder = FieldDefinition.newFieldDefinition()
                .name("$prefix$sourceTypeName$targetFieldName")
                .inputValueDefinition(input(sourceIdField.name, idType))
                .inputValueDefinition(input(targetField.name, targetIDType))
                .type(type)

            val startIdField = RelationshipInfo.RelatedField(sourceIdField.name, sourceIdField, sourceNodeType)
            val endIdField = RelationshipInfo.RelatedField(targetField.name, targetIdField, targetNodeType)
            return handlerFactory(sourceNodeType, relation, startIdField, endIdField, targetField, fieldDefinitionBuilder)
        }
    }


    fun getRelationSelect(start: Boolean, arguments: Map<String, Argument>): Cypher {
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
                idField.field, (relFieldName + idField.argumentName.capitalize()).quote())
    }

}