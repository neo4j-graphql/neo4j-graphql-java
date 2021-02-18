package org.neo4j.graphql.handler.relation

import graphql.Scalars
import graphql.language.Argument
import graphql.schema.*
import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Node
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.BaseDataFetcherForContainer

abstract class BaseRelationHandler(
        type: GraphQLFieldsContainer,
        val relation: RelationshipInfo,
        private val startId: RelationshipInfo.RelatedField,
        private val endId: RelationshipInfo.RelatedField,
        fieldDefinition: GraphQLFieldDefinition)
    : BaseDataFetcherForContainer(type, fieldDefinition) {

    init {
        propertyFields.remove(startId.argumentName)
        propertyFields.remove(endId.argumentName)
    }

    abstract class BaseRelationFactory(val prefix: String, schemaConfig: SchemaConfig) : AugmentationHandler(schemaConfig) {

        protected fun buildFieldDefinition(
                source: GraphQLFieldsContainer,
                targetField: GraphQLFieldDefinition,
                nullableResult: Boolean
        ): GraphQLFieldDefinition.Builder? {

            val targetType = targetField.type.getInnerFieldsContainer()
            val sourceIdField = source.getIdField()
            val targetIdField = targetType.getIdField()
            if (sourceIdField == null || targetIdField == null) {
                return null
            }

            val targetFieldName = targetField.name.capitalize()
            val idType = GraphQLNonNull(Scalars.GraphQLID)
            val targetIDType = if (targetField.type.isList()) GraphQLNonNull(GraphQLList(idType)) else idType


            var type: GraphQLOutputType = source
            if (!nullableResult) {
                type = GraphQLNonNull(type)
            }

            return GraphQLFieldDefinition.newFieldDefinition()
                .name("$prefix${source.name}$targetFieldName")
                .argument(input(sourceIdField.name, idType))
                .argument(input(targetField.name, targetIDType))
                .type(type.ref() as GraphQLOutputType)
        }

        protected fun canHandleType(type: GraphQLFieldsContainer): Boolean {
            if (type !is GraphQLObjectType) {
                return false
            }
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(type.name)) {
                // TODO we do not mutate the node but the relation, I think this check should be different
                return false
            }
            if (type.getIdField() == null) {
                return false
            }
            return true
        }

        protected fun canHandleField(targetField: GraphQLFieldDefinition): Boolean {
            val type = targetField.type.inner() as? GraphQLObjectType ?: return false
            if (targetField.getDirective(DirectiveConstants.RELATION) == null) {
                return false
            }
            if (type.getIdField() == null) {
                return false
            }
            return true
        }

        final override fun createDataFetcher(operationType: OperationType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            if (operationType != OperationType.MUTATION) {
                return null
            }
            if (fieldDefinition.cypherDirective() != null) {
                return null
            }
            val sourceType = fieldDefinition.type.inner() as? GraphQLFieldsContainer
                    ?: return null
            if (!canHandleType(sourceType)) {
                return null
            }

            val p = "$prefix${sourceType.name}"
            if (!fieldDefinition.name.startsWith(p)) {
                return null
            }
            val targetField = fieldDefinition.name
                .removePrefix(p)
                .decapitalize()
                .let { sourceType.getFieldDefinition(it) }
                    ?: return null
            if (!canHandleField(targetField)) {
                return null
            }
            val relation = sourceType.relationshipFor(targetField.name) ?: return null

            val targetType = targetField.type.getInnerFieldsContainer()
            val sourceIdField = sourceType.getIdField()
            val targetIdField = targetType.getIdField()
            if (sourceIdField == null || targetIdField == null) {
                return null
            }
            val startIdField = RelationshipInfo.RelatedField(sourceIdField.name, sourceIdField, sourceType)
            val endIdField = RelationshipInfo.RelatedField(targetField.name, targetIdField, targetType)
            return createDataFetcher(sourceType, relation, startIdField, endIdField, fieldDefinition)
        }

        abstract fun createDataFetcher(
                sourceType: GraphQLFieldsContainer,
                relation: RelationshipInfo,
                startIdField: RelationshipInfo.RelatedField,
                endIdField: RelationshipInfo.RelatedField,
                fieldDefinition: GraphQLFieldDefinition
        ): DataFetcher<Cypher>?

    }

    fun getRelationSelect(start: Boolean, arguments: Map<String, Argument>): Pair<Node, Condition> {
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
            throw IllegalArgumentException("No ID for the ${if (start) "start" else "end"} Type provided, ${idField.argumentName} is required")
        }
        val (rel, where) = getSelectQuery(relFieldName, idField.declaringType.label(), arguments[idField.argumentName],
                idField.field, false)
        return (rel as? Node
                ?: throw IllegalStateException("Expected type to be of type node")) to where
    }

}
