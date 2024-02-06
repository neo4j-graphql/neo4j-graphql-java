package org.neo4j.graphql.schema.model.inputs.disconnect

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.InputListWrapper
import org.neo4j.graphql.schema.model.inputs.PerNodeInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere.InterfaceConnectionWhere
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere.NodeConnectionWhere
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation
import org.neo4j.graphql.toDict

sealed interface DisconnectFieldInput {

    sealed interface ImplementingTypeDisconnectFieldInput {
        val where: ConnectionWhere?
        val disconnect: List<DisconnectInput>?
    }

    class NodeDisconnectFieldInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeDisconnectFieldInput {
        override val where = data.nestedDict(Constants.WHERE)
            ?.let { NodeConnectionWhere(node, relationshipProperties, it) }

        override val disconnect = data.nestedDictList(Constants.DISCONNECT_FIELD)
            .map { DisconnectInput.NodeDisconnectInput(node, it) }
            .takeIf { it.isNotEmpty() }


        object Augmentation : AugmentationBase {
            fun generateFieldDisconnectFieldInputIT(
                rel: RelationField,
                node: Node,
                ctx: AugmentationContext
            ): String? =
                ctx.getOrCreateInputObjectType(rel.namings.getDisconnectFieldInputTypeName(node)) { fields, _ ->

                    NodeConnectionWhere.Augmentation
                        .generateFieldConnectionWhereIT(rel, node, ctx)
                        ?.let { fields += inputValue(Constants.WHERE, it.asType()) }

                    DisconnectInput.NodeDisconnectInput.Augmentation
                        .generateContainerDisconnectInputIT(node, ctx)
                        ?.let { fields += inputValue(Constants.DISCONNECT_FIELD, it.asType()) }
                }
        }
    }

    class InterfaceDisconnectFieldInput(
        interfaze: Interface,
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) :
        ImplementingTypeDisconnectFieldInput {

        override val where = data.nestedDict(Constants.WHERE)
            ?.let { InterfaceConnectionWhere(interfaze, relationshipProperties, it) }

        override val disconnect = data.nestedDictList(Constants.DISCONNECT_FIELD)
            .map { DisconnectInput.InterfaceDisconnectInput(interfaze, it) }
            .takeIf { it.isNotEmpty() }

        val on = data.nestedDict(Constants.ON)?.let {
            PerNodeInput(
                interfaze,
                it,
                { node: Node, value: Any -> NodeDisconnectFieldInputs.create(node, relationshipProperties, value) }
            )
        }

        object Augmentation : AugmentationBase {
            fun generateFieldDisconnectIT(
                rel: RelationField,
                interfaze: Interface,
                ctx: AugmentationContext
            ) =
                ctx.getOrCreateInputObjectType(rel.namings.getDisconnectFieldInputTypeName(interfaze)) { fields, _ ->

                    ctx.addInterfaceField(
                        interfaze,
                        interfaze.namings.disconnectInputTypeName,
                        interfaze.namings.whereOnImplementationsDisconnectInputTypeName,
                        { node ->
                            DisconnectInput.NodeDisconnectInput.Augmentation.generateContainerDisconnectInputIT(
                                node,
                                ctx
                            )
                        },
                        RelationFieldBaseAugmentation::generateFieldDisconnectIT
                    )
                        ?.let { fields += inputValue(Constants.DISCONNECT_FIELD, it.asType()) }

                    InterfaceConnectionWhere.Augmentation.generateFieldConnectionWhereIT(rel, interfaze, ctx)?.let {
                        fields += inputValue(Constants.WHERE, it.asType())
                    }
                }
        }
    }

    class NodeDisconnectFieldInputs(items: List<NodeDisconnectFieldInput>) : DisconnectFieldInput,
        InputListWrapper<NodeDisconnectFieldInput>(items) {

        companion object {
            fun create(node: Node, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::NodeDisconnectFieldInputs,
                { NodeDisconnectFieldInput(node, relationshipProperties, it.toDict()) }
            )
        }
    }

    class InterfaceDisconnectFieldInputs(items: List<InterfaceDisconnectFieldInput>) : DisconnectFieldInput,
        InputListWrapper<InterfaceDisconnectFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::InterfaceDisconnectFieldInputs,
                { InterfaceDisconnectFieldInput(interfaze, relationshipProperties, it.toDict()) }
            )
        }
    }

    class UnionDisconnectFieldInput(union: Union, relationshipProperties: RelationshipProperties?, data: Dict) :
        DisconnectFieldInput,
        PerNodeInput<NodeDisconnectFieldInputs>(
            union,
            data,
            { node, value -> NodeDisconnectFieldInputs.create(node, relationshipProperties, value) }
        )

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeDisconnectFieldInputs.create(it, field.properties, value) },
            onInterface = { InterfaceDisconnectFieldInputs.create(it, field.properties, value) },
            onUnion = { UnionDisconnectFieldInput(it, field.properties, value.toDict()) }
        )
    }
}
