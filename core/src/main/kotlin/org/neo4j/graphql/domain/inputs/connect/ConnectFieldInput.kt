package org.neo4j.graphql.domain.inputs.connect

import org.neo4j.graphql.*
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.InputListWrapper
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.ScalarProperties
import org.neo4j.graphql.domain.inputs.create.CreateInput
import org.neo4j.graphql.schema.InterfaceAugmentation
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation

sealed interface ConnectFieldInput {

    sealed class ImplementingTypeConnectFieldInput(
        implementingType: ImplementingType,
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) {
        val edge = relationshipProperties
            ?.let { props -> data[Constants.EDGE_FIELD]?.let { ScalarProperties.create(data, props) } }

        val where = data[Constants.WHERE]?.let { ConnectWhere(implementingType, Dict(it)) }
        val connect =
            data[Constants.CONNECT_FIELD]?.let { input ->
                input.wrapList().map { ConnectInput.create(implementingType, it) }
            }
    }

    class NodeConnectFieldInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeConnectFieldInput(node, relationshipProperties, data) {

        object Augmentation {
            fun generateFieldConnectFieldInputIT(
                rel: RelationField,
                prefix: String,
                node: Node,
                ctx: AugmentationContext
            ) = ctx.getOrCreateInputObjectType(prefix + Constants.InputTypeSuffix.ConnectFieldInput) { fields, _ ->

                ConnectWhere.Augmentation.generateConnectWhereIT(node, ctx)
                    ?.let { fields += ctx.inputValue(Constants.WHERE, it.asType()) }

                ConnectInput.NodeConnectInput.Augmentation.generateContainerConnectInputIT(node, ctx)
                    ?.let { fields += ctx.inputValue(Constants.CONNECT_FIELD, it.wrapType(rel)) }

                CreateInput.Augmentation.addEdgePropertyCreateInputField(rel.properties, fields, ctx,
                    required = { it.hasRequiredNonGeneratedFields })
            }
        }
    }

    class InterfaceConnectFieldInput(
        interfaze: Interface,
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) :
        ImplementingTypeConnectFieldInput(interfaze, relationshipProperties, data) {
        val on = data[Constants.ON]?.let {
            PerNodeInput(
                interfaze,
                Dict(it),
                { node: Node, value: Any ->
                    ConnectFieldInput.NodeConnectFieldInputs.create(
                        node,
                        relationshipProperties,
                        value
                    )
                }
            )
        }

        object Augmentation {
            fun generateFieldConnectIT(
                rel: RelationField,
                prefix: String,
                interfaze: Interface,
                ctx: AugmentationContext
            ) = ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.ConnectFieldInput}") { fields, _ ->

                InterfaceAugmentation(interfaze, ctx)
                    .addInterfaceField(
                        Constants.InputTypeSuffix.ConnectInput,
                        { node ->
                            ConnectInput.NodeConnectInput.Augmentation.generateContainerConnectInputIT(
                                node,
                                ctx
                            )
                        },
                        RelationFieldBaseAugmentation::generateFieldConnectIT
                    )
                    ?.let { fields += ctx.inputValue(Constants.CONNECT_FIELD, it.asType()) }

                CreateInput.Augmentation.addEdgePropertyCreateInputField(
                    rel.properties,
                    fields,
                    ctx,
                    required = { it.hasRequiredFields })

                ConnectWhere.Augmentation.generateConnectWhereIT(interfaze, ctx)
                    ?.let { fields += ctx.inputValue(Constants.WHERE, it.asType()) }
            }
        }
    }

    class NodeConnectFieldInputs(items: List<NodeConnectFieldInput>) : ConnectFieldInput,
        InputListWrapper<NodeConnectFieldInput>(items) {

        companion object {
            fun create(node: Node, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::NodeConnectFieldInputs,
                { NodeConnectFieldInput(node, relationshipProperties, Dict(it)) }
            )
        }
    }

    class InterfaceConnectFieldInputs(items: List<InterfaceConnectFieldInput>) : ConnectFieldInput,
        InputListWrapper<InterfaceConnectFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::InterfaceConnectFieldInputs,
                {
                    InterfaceConnectFieldInput(
                        interfaze,
                        relationshipProperties,
                        Dict(it)
                    )
                }
            )
        }
    }

    class UnionConnectFieldInput(union: Union, relationshipProperties: RelationshipProperties?, data: Dict) :
        ConnectFieldInput,
        PerNodeInput<NodeConnectFieldInputs>(
            union,
            data,
            { node, value -> NodeConnectFieldInputs.create(node, relationshipProperties, value) }
        )

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeConnectFieldInputs.create(it, field.properties, value) },
            onInterface = { InterfaceConnectFieldInputs.create(it, field.properties, value) },
            onUnion = { UnionConnectFieldInput(it, field.properties, Dict(value)) }
        )
    }
}
