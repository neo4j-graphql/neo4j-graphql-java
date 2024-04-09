package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.language.NonNullType
import graphql.language.Type
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Entity
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.directives.SubscriptionDirective.SubscriptionEventType
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler
import org.neo4j.graphql.schema.model.inputs.subscription.SubscriptionRelationshipWhere
import org.neo4j.graphql.schema.model.inputs.subscription.SubscriptionWhere
import org.neo4j.graphql.schema.model.outputs.FieldContainerSelection

class SubscriptionResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node,
    val eventType: SubscriptionEventType
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.NodeAugmentation {
        override fun augmentNode(node: Node): List<AugmentedField> {
            if (!ctx.schemaConfig.features.subscriptions || node.annotations.subscription?.isSubscribable == false) {
                return emptyList()
            }
            val result = mutableListOf<AugmentedField?>()

            if (node.annotations.subscription?.created != false) {
                result += addSubscriptionField(
                    node,
                    node.namings.rootTypeFieldNames.subscribe.created,
                    node.namings.subscriptionEventTypeNames.create,
                    node.namings.subscriptionEventPayloadFieldNames.create,
                    SubscriptionEventType.CREATED,
                    SubscriptionWhere.Augmentation.generateWhereIT(node, ctx)
                )
            }

            if (node.annotations.subscription?.updated != false) {
                result += addSubscriptionField(
                    node,
                    node.namings.rootTypeFieldNames.subscribe.updated,
                    node.namings.subscriptionEventTypeNames.update,
                    node.namings.subscriptionEventPayloadFieldNames.update,
                    SubscriptionEventType.UPDATED,
                    SubscriptionWhere.Augmentation.generateWhereIT(node, ctx)
                ) { fields, _ ->
                    getEventPayloadType(node)?.let {
                        fields += field(Constants.PREVIOUS_STATE, it.asRequiredType())
                    }
                }
            }

            if (node.annotations.subscription?.deleted != false) {
                result += addSubscriptionField(
                    node,
                    node.namings.rootTypeFieldNames.subscribe.deleted,
                    node.namings.subscriptionEventTypeNames.delete,
                    node.namings.subscriptionEventPayloadFieldNames.delete,
                    SubscriptionEventType.DELETED,
                    SubscriptionWhere.Augmentation.generateWhereIT(node, ctx)
                )
            }

            if (node.relationBaseFields.isNotEmpty()) {
                if (node.annotations.subscription?.relationshipCreated != false) {
                    result += addRelationSubscriptionField(
                        node,
                        node.namings.rootTypeFieldNames.subscribe.relationshipCreated,
                        node.namings.subscriptionEventTypeNames.createRelationship,
                        node.namings.subscriptionEventPayloadFieldNames.createRelationship,
                        SubscriptionEventType.RELATIONSHIP_CREATED
                    )
                }

                if (node.annotations.subscription?.relationshipDeleted != false) {
                    result += addRelationSubscriptionField(
                        node,
                        node.namings.rootTypeFieldNames.subscribe.relationshipDeleted,
                        node.namings.subscriptionEventTypeNames.deleteRelationship,
                        node.namings.subscriptionEventPayloadFieldNames.deleteRelationship,
                        SubscriptionEventType.RELATIONSHIP_DELETED
                    )
                }
            }
            return result.filterNotNull()
        }

        private fun getEventPayloadType(entity: Entity): String? {
            return entity.extractOnTarget(
                { getEventPayloadType(it) },
                { getEventPayloadType(it) },
            )
        }

        private fun getEventPayloadType(union: Union): String? {
            return ctx.getOrCreateUnionType(union.namings.subscriptionEventPayloadTypeName) { members, _ ->
                union.nodes.values.forEach { node ->
                    getEventPayloadType(node)?.let { members += it.asType() }
                }
            }
        }

        private fun getEventPayloadType(implementingType: ImplementingType): String? {
            val initExtensions = { implementz: (Type<*>) -> Unit ->
                implementingType.interfaces.forEach { interfaze ->
                    getEventPayloadType(interfaze)?.let { implementz(it.asType()) }
                }
            }

            val initFields = { fields: MutableList<FieldDefinition>, _: String ->
                implementingType.fields.filter { it.isEventPayloadField() }
                    .forEach {
                        fields += field(it.fieldName, it.typeMeta.type) {
                            directives(it.annotations.otherDirectives)
                        }
                    }
            }

            return implementingType.extractOnImplementingType(
                onNode = { node ->
                    ctx.getOrCreateObjectType(
                        node.namings.subscriptionEventPayloadTypeName,
                        { initExtensions { implementz(it) } },
                        initFields
                    )
                },
                onInterface = { interfaze ->
                    ctx.getOrCreateInterfaceType(
                        interfaze.namings.subscriptionEventPayloadTypeName,
                        { initExtensions { implementz(it) } },
                        initFields
                    )
                }
            )
        }

        private fun getRelationsEventPayloadType(node: Node) =
            ctx.getOrCreateObjectType(node.namings.subscriptionRelationsEventPayloadTypeName) { fields, _ ->
                node.relationFields.forEach { relationField ->
                    getRelationsEventPayloadType(relationField)?.let {
                        fields += field(relationField.fieldName, it.asType())
                    }
                }
            }

        private fun getRelationsEventPayloadType(relationField: RelationField) =
            ctx.getOrCreateObjectType(relationField.namings.subscriptionConnectedRelationshipTypeName) { fields, _ ->

                //TODO Darell: these fields should be on a edge type to not have naming conflicts
                relationField.properties?.fields?.forEach {
                    fields += FieldContainerSelection.Augmentation.mapField(it, ctx)
                }

                getEventPayloadType(relationField.target)
                    ?.let { fields += field(Constants.NODE_FIELD, it.asRequiredType()) }
            }

        private fun addRelationSubscriptionField(
            node: Node,
            fieldName: String,
            typeName: String,
            eventPayloadFieldName: String,
            eventType: SubscriptionEventType
        ): AugmentedField {
            val (type, nestedFiledName) = when (eventType) {
                SubscriptionEventType.RELATIONSHIP_CREATED -> SubscriptionRelationshipWhere.Type.Created to Constants.CREATED_RELATIONSHIP
                SubscriptionEventType.RELATIONSHIP_DELETED -> SubscriptionRelationshipWhere.Type.Deleted to Constants.DELETED_RELATIONSHIP
                else -> throw IllegalArgumentException("Invalid event type $eventType")
            }
            return addSubscriptionField(
                node,
                fieldName,
                typeName,
                eventPayloadFieldName,
                eventType,
                SubscriptionRelationshipWhere.Augmentation.generateSubscriptionConnectionWhereType(node, type, ctx)
            ) { fields, hasEventPayloadFields ->

                if (hasEventPayloadFields) {
                    fields += field(Constants.RELATIONSHIP_FIELD_NAME, Constants.Types.String.makeRequired())
                }
                getRelationsEventPayloadType(node)?.let {
                    fields += field(nestedFiledName, it.asRequiredType())
                }
            }
        }

        private fun addSubscriptionField(
            node: Node,
            fieldName: String,
            typeName: String,
            eventPayloadFieldName: String,
            eventType: SubscriptionEventType,
            whereIT: String?,
            initFields: (fields: MutableList<FieldDefinition>, hasEventPayloadFields: Boolean) -> Unit = { _, _ -> },
        ): AugmentedField {
            ctx.getOrCreateObjectType(typeName) { fields, _ ->
                fields += field(Constants.EVENT, NonNullType(Constants.Types.EventType))
                fields += field(Constants.TIMESTAMP, NonNullType(Constants.Types.Float)) // TODO INT?
                val eventPayloadType = getEventPayloadType(node)
                eventPayloadType?.let {
                    fields += field(eventPayloadFieldName, it.asRequiredType())
                }
                initFields(fields, eventPayloadType != null)
            }

            val coords = addSubscriptionField(fieldName, typeName.asRequiredType()) { args ->
                whereIT?.let { args += inputValue(Constants.WHERE, it.asType()) }
            }

            return AugmentedField(coords, SubscriptionResolver(ctx.schemaConfig, node, eventType))
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        TODO()
    }
}
