package org.neo4j.graphql.handler.relation

import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLType
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Node
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.BaseDataFetcherForContainer

/**
 * This is a base class for all handler acting on relations / edges
 */
abstract class BaseRelationHandler(private val prefix: String, schemaConfig: SchemaConfig) : BaseDataFetcherForContainer(schemaConfig) {

    lateinit var relation: RelationshipInfo<GraphQLFieldsContainer>
    lateinit var startId: RelatedField
    lateinit var endId: RelatedField

    data class RelatedField(
            val argumentName: String,
            val field: GraphQLFieldDefinition,
            val declaringType: GraphQLFieldsContainer
    )

    abstract class BaseRelationFactory(
            private val prefix: String,
            schemaConfig: SchemaConfig,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
    ) : AugmentationHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

        protected fun buildFieldDefinition(
                source: ImplementingTypeDefinition<*>,
                targetField: FieldDefinition,
                nullableResult: Boolean
        ): FieldDefinition.Builder? {

            val (sourceIdField, _) = getRelationFields(source, targetField) ?: return null

            val targetFieldName = targetField.name.capitalize()
            val idType = NonNullType(TypeID)
            val targetIDType = if (targetField.type.isList()) NonNullType(ListType(idType)) else idType


            var type: Type<*> = TypeName(source.name)
            if (!nullableResult) {
                type = NonNullType(type)
            }

            return FieldDefinition.newFieldDefinition()
                .name("$prefix${source.name}$targetFieldName")
                .inputValueDefinition(input(sourceIdField.name, idType))
                .inputValueDefinition(input(targetField.name, targetIDType))
                .type(type)
        }

        protected fun canHandleType(type: ImplementingTypeDefinition<*>): Boolean {
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(type.name) || isRootType(type)) {
                // TODO we do not mutate the node but the relation, I think this check should be different
                return false
            }
            if (type.getIdField() == null) {
                return false
            }
            return true
        }

        protected fun canHandleField(targetField: FieldDefinition): Boolean {
            val type = targetField.type.inner().resolve() as? ImplementingTypeDefinition<*> ?: return false
            if (!targetField.hasDirective(DirectiveConstants.RELATION)) {
                return false
            }
            if (targetField.isIgnored()) {
                return false
            }
            if (type.getIdField() == null) {
                return false
            }
            return true
        }

        final override fun createDataFetcher(operationType: OperationType, fieldDefinition: FieldDefinition): DataFetcher<Cypher>? {
            if (operationType != OperationType.MUTATION) {
                return null
            }
            if (fieldDefinition.cypherDirective() != null) {
                return null
            }
            val sourceType = fieldDefinition.type.inner().resolve() as? ImplementingTypeDefinition<*> ?: return null
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
            if (!sourceType.hasRelationshipFor(targetField.name)) {
                return null
            }

            val (_, _) = getRelationFields(sourceType, targetField) ?: return null
            return createDataFetcher()
        }

        private fun ImplementingTypeDefinition<*>.hasRelationshipFor(name: String): Boolean {
            val field = getFieldDefinition(name)
                    ?: throw IllegalArgumentException("$name is not defined on ${this.name}")
            val fieldObjectType = field.type.inner().resolve() as? ImplementingTypeDefinition<*> ?: return false

            val target = getDirectiveArgument<String>(DirectiveConstants.RELATION, DirectiveConstants.RELATION_TO, null)
            if (target != null) {
                if (fieldObjectType.getFieldDefinition(target)?.name == this.name) return true
            } else {
                if (fieldObjectType.getDirective(DirectiveConstants.RELATION) != null) return true
                if (field.getDirective(DirectiveConstants.RELATION) != null) return true
            }
            return false
        }

        abstract fun createDataFetcher(): DataFetcher<Cypher>?

        private fun getRelationFields(source: ImplementingTypeDefinition<*>, targetField: FieldDefinition): Pair<FieldDefinition, FieldDefinition>? {
            val targetType = targetField.type.inner().resolve() as? ImplementingTypeDefinition<*> ?: return null
            val sourceIdField = source.getIdField()
            val targetIdField = targetType.getIdField()
            if (sourceIdField == null || targetIdField == null) {
                return null
            }
            return sourceIdField to targetIdField
        }
    }

    override fun initDataFetcher(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType) {
        super.initDataFetcher(fieldDefinition, parentType)

        initRelation(fieldDefinition)

        propertyFields.remove(startId.argumentName)
        propertyFields.remove(endId.argumentName)
    }

    protected open fun initRelation(fieldDefinition: GraphQLFieldDefinition) {
        val p = "$prefix${type.name}"

        val targetField = fieldDefinition.name
            .removePrefix(p)
            .decapitalize()
            .let {
                type.getRelevantFieldDefinition(it) ?: throw IllegalStateException("Cannot find field $it on type ${type.name}")
            }


        relation = type.relationshipFor(targetField.name)
                ?: throw IllegalStateException("Cannot resolve relationship for ${targetField.name} on type ${type.name}")

        val targetType = targetField.type.getInnerFieldsContainer()
        val sourceIdField = type.getIdField()
                ?: throw IllegalStateException("Cannot find id field for type ${type.name}")
        val targetIdField = targetType.getIdField()
                ?: throw IllegalStateException("Cannot find id field for type ${targetType.name}")
        startId = RelatedField(sourceIdField.name, sourceIdField, type)
        endId = RelatedField(targetField.name, targetIdField, targetType)
    }

    fun getRelationSelect(start: Boolean, arguments: Map<String, Argument>): Pair<Node, Condition> {
        val relFieldName: String
        val idField: RelatedField
        if (start) {
            relFieldName = relation.startField
            idField = startId
        } else {
            relFieldName = relation.endField
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
