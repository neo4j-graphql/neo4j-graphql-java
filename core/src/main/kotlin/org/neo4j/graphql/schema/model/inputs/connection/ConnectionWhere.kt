package org.neo4j.graphql.schema.model.inputs.connection

import graphql.language.InputValueDefinition
import graphql.language.ListType
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.predicates.ConnectionPredicate
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.NestedWhere
import org.neo4j.graphql.schema.model.inputs.PerNodeInput
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation

sealed interface ConnectionWhere {

    sealed class ImplementingTypeConnectionWhere<T : ImplementingTypeConnectionWhere<T>>(
        implementingType: ImplementingType,
        relationshipProperties: RelationshipProperties?,
        data: Dict,
        nestedWhereFactory: (data: Dict) -> T?
    ) : ConnectionWhere, NestedWhere<T>(data, nestedWhereFactory) {

        val predicates: List<ConnectionPredicate>? = ConnectionPredicate.getTargetOperationCombinations()
            .mapNotNull { (target, op) ->
                val key = target.targetName + op.suffix
                data.nestedDict(key)?.let { input ->
                    when (target) {
                        ConnectionPredicate.Target.NODE -> WhereInput.create(implementingType, input)
                        ConnectionPredicate.Target.EDGE -> WhereInput.create(relationshipProperties, input)
                    }
                }?.let { ConnectionPredicate(key, target, op, it) }
            }.takeIf { it.isNotEmpty() }

        object Augmentation : AugmentationBase {
            fun addEdgePredicates(
                rel: RelationField,
                ctx: AugmentationContext,
                fields: MutableList<InputValueDefinition>
            ) {
                WhereInput.EdgeWhereInput.Augmentation
                    .generateRelationPropertiesWhereIT(rel.properties, ctx)
                    ?.let {
                        fields += inputValue(Constants.EDGE_FIELD, it.asType())
                        fields += inputValue(Constants.EDGE_FIELD + "_NOT", it.asType())
                    }

            }

            fun addNestingWhere(
                fields: MutableList<InputValueDefinition>,
                connectionWhereName: String
            ) {
                if (fields.isNotEmpty()) {
                    val listWhereType = ListType(connectionWhereName.asRequiredType())
                    fields += inputValue(Constants.AND, listWhereType)
                    fields += inputValue(Constants.OR, listWhereType)
                }
            }
        }

    }

    class NodeConnectionWhere(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeConnectionWhere<NodeConnectionWhere>(
            node,
            relationshipProperties,
            data,
            { NodeConnectionWhere(node, relationshipProperties, it) }
        ) {
        object Augmentation : AugmentationBase {
            fun generateFieldConnectionWhereIT(
                rel: RelationField,
                prefix: String,
                node: Node,
                ctx: AugmentationContext
            ) =

                ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.ConnectionWhere}") { fields, connectionWhereName ->
                    WhereInput.NodeWhereInput.Augmentation.generateWhereIT(node, ctx)
                        ?.let {
                            fields += inputValue(Constants.NODE_FIELD, it.asType())
                            fields += inputValue(Constants.NODE_FIELD + "_NOT", it.asType())
                        }

                    ImplementingTypeConnectionWhere.Augmentation.addEdgePredicates(rel, ctx, fields)
                    ImplementingTypeConnectionWhere.Augmentation.addNestingWhere(fields, connectionWhereName)
                }
        }
    }

    class InterfaceConnectionWhere(
        interfaze: Interface,
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) :
        ImplementingTypeConnectionWhere<InterfaceConnectionWhere>(
            interfaze,
            relationshipProperties,
            data,
            { InterfaceConnectionWhere(interfaze, relationshipProperties, it) }
        ) {
        val on = data.nestedDict(Constants.ON)
            ?.let { PerNodeInput(interfaze, it, { node: Node, value: Any -> WhereInput.create(node, value.toDict()) }) }

        object Augmentation : AugmentationBase {
            fun generateFieldConnectionWhereIT(
                rel: RelationField,
                interfaze: Interface,
                ctx: AugmentationContext
            ) =
                ctx.getOrCreateInputObjectType(rel.connectionField.typeMeta.whereType.name()) { fields, connectionWhereName ->
                    WhereInput.InterfaceWhereInput.Augmentation.generateFieldWhereIT(interfaze, ctx)?.let {
                        fields += inputValue(Constants.NODE_FIELD, it.asType())
                        fields += inputValue(Constants.NODE_FIELD + "_NOT", it.asType())
                    }

                    ImplementingTypeConnectionWhere.Augmentation.addEdgePredicates(rel, ctx, fields)
                    ImplementingTypeConnectionWhere.Augmentation.addNestingWhere(fields, connectionWhereName)
                }

        }
    }

    class UnionConnectionWhere(union: Union, relationshipProperties: RelationshipProperties?, data: Dict) :
        ConnectionWhere,
        PerNodeInput<NodeConnectionWhere>(
            union,
            data,
            { node, value -> NodeConnectionWhere(node, relationshipProperties, value.toDict()) }
        )

    companion object {

        fun create(implementingType: ImplementingType, relationshipProperties: RelationshipProperties?, value: Any) =
            when (implementingType) {
                is Node -> NodeConnectionWhere(implementingType, relationshipProperties, value.toDict())
                is Interface -> InterfaceConnectionWhere(implementingType, relationshipProperties, value.toDict())
            }

        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onImplementingType = { create(it, field.properties, value.toDict()) },
            onUnion = { UnionConnectionWhere(it, field.properties, value.toDict()) }
        )
    }

    object Augmentation : AugmentationBase {

        fun generateConnectionWhereIT(field: ConnectionField, ctx: AugmentationContext): String? =
            ctx.getTypeFromRelationField(
                field.relationshipField,
                RelationFieldBaseAugmentation::generateFieldConnectionWhereIT
            )
    }
}
