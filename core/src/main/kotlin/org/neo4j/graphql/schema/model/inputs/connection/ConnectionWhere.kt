package org.neo4j.graphql.schema.model.inputs.connection

import graphql.language.InputValueDefinition
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.domain.predicates.ConnectionPredicate
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.NestedWhere
import org.neo4j.graphql.schema.model.inputs.PerNodeInput
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation
import org.neo4j.graphql.toDict

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
                        ConnectionPredicate.Target.NODE -> WhereInput.create(
                            implementingType as FieldContainer<*>,
                            input
                        )

                        ConnectionPredicate.Target.EDGE -> WhereInput.create(relationshipProperties, input)
                    }
                }?.let { ConnectionPredicate(key, target, op, it) }
            }.takeIf { it.isNotEmpty() }

        object Augmentation : AugmentationBase {
            fun addEdgePredicates(
                rel: RelationBaseField,
                ctx: AugmentationContext,
                fields: MutableList<InputValueDefinition>
            ) {
                WhereInput.EdgeWhereInput.Augmentation
                    .generateRelationPropertiesWhereIT((rel.interfaceField as? RelationBaseField) ?: rel, ctx)
                    ?.let { fields += inputValue(Constants.EDGE_FIELD, it) }

            }
        }

    }

    class NodeConnectionWhere(val node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeConnectionWhere<NodeConnectionWhere>(
            node,
            relationshipProperties,
            data,
            { NodeConnectionWhere(node, relationshipProperties, it) }
        ) {
        object Augmentation : AugmentationBase {
            fun generateFieldConnectionWhereIT(
                rel: RelationBaseField,
                node: Node,
                ctx: AugmentationContext
            ) =

                ctx.getOrCreateInputObjectType(rel.namings.getConnectionWhereTypename(node)) { fields, connectionWhereName ->
                    WhereInput.NodeWhereInput.Augmentation.generateWhereIT(node, ctx)
                        ?.let {
                            fields += inputValue(Constants.NODE_FIELD, it.asType())
                        }

                    ImplementingTypeConnectionWhere.Augmentation.addEdgePredicates(rel, ctx, fields)
                    WhereInput.Augmentation.addNestingWhereFields(connectionWhereName, fields)
                }
        }
    }

    class InterfaceConnectionWhere(
        val interfaze: Interface,
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) :
        ImplementingTypeConnectionWhere<InterfaceConnectionWhere>(
            interfaze,
            relationshipProperties,
            data,
            { InterfaceConnectionWhere(interfaze, relationshipProperties, it) }
        ) {

        object Augmentation : AugmentationBase {
            fun generateFieldConnectionWhereIT(
                rel: RelationBaseField,
                interfaze: Interface,
                ctx: AugmentationContext
            ) =
                ctx.getOrCreateInputObjectType(rel.namings.getConnectionWhereTypename(interfaze)) { fields, connectionWhereName ->
                    WhereInput.InterfaceWhereInput.Augmentation.generateFieldWhereIT(interfaze, ctx)?.let {
                        fields += inputValue(Constants.NODE_FIELD, it.asType())
                    }

                    ImplementingTypeConnectionWhere.Augmentation.addEdgePredicates(rel, ctx, fields)
                    WhereInput.Augmentation.addNestingWhereFields(connectionWhereName, fields)
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

        fun create(field: RelationBaseField, value: Any) = field.extractOnTarget(
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
