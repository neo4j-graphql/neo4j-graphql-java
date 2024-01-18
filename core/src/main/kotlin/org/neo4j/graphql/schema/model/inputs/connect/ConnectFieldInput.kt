package org.neo4j.graphql.schema.model.inputs.connect

import graphql.language.BooleanValue
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.InputListWrapper
import org.neo4j.graphql.schema.model.inputs.PerNodeInput
import org.neo4j.graphql.schema.model.inputs.ScalarProperties
import org.neo4j.graphql.schema.model.inputs.create.CreateInput
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation

sealed interface ConnectFieldInput {

    sealed class ImplementingTypeConnectFieldInput(
        implementingType: ImplementingType,
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) {
        val edge = relationshipProperties
            ?.let { props -> data.nestedDict(Constants.EDGE_FIELD)?.let { ScalarProperties.create(it, props) } }

        val where = data.nestedDict(Constants.WHERE)?.let { ConnectWhere(implementingType, it) }

        val connect = data.nestedDictList(Constants.CONNECT_FIELD)
            .map { ConnectInput.create(implementingType, it) }
            .takeIf { it.isNotEmpty() }
    }

    class NodeConnectFieldInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeConnectFieldInput(node, relationshipProperties, data) {

        object Augmentation : AugmentationBase {
            fun generateFieldConnectFieldInputIT(
                rel: RelationField,
                node: Node,
                ctx: AugmentationContext
            ) = ctx.getOrCreateInputObjectType(rel.operations.getConnectFieldInputTypeName(node)) { fields, _ ->

                ConnectWhere.Augmentation.generateConnectWhereIT(node, ctx)
                    ?.let { fields += inputValue(Constants.WHERE, it.asType()) }

                ConnectInput.NodeConnectInput.Augmentation.generateContainerConnectInputIT(node, ctx)
                    ?.let {
                        fields += inputValue(Constants.CONNECT_FIELD, it.wrapType(rel))
                    }

                if (rel.node != null) {
                    fields += inputValue(Constants.OVERWRITE_FIELD, Constants.Types.Boolean.makeRequired()) {
                        defaultValue(BooleanValue(true))
                        description("Whether or not to overwrite any matching relationship with the new properties.".asDescription())
                    }
                }

                CreateInput.Augmentation.addEdgePropertyCreateInputField(
                    rel, fields, ctx,
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
        val on = data.nestedDict(Constants.ON)
            ?.let {
                PerNodeInput(
                    interfaze,
                    it,
                    { node: Node, value: Any -> NodeConnectFieldInputs.create(node, relationshipProperties, value) }
                )
            }

        object Augmentation : AugmentationBase {
            fun generateFieldConnectIT(
                rel: RelationField,
                interfaze: Interface,
                ctx: AugmentationContext,
                name: String = rel.operations.getConnectFieldInputTypeName(interfaze)
            ) = ctx.getOrCreateInputObjectType(name) { fields, _ ->

                ctx.addInterfaceField(
                    interfaze,
                    interfaze.operations.connectInputTypeName,
                    interfaze.operations.whereOnImplementationsConnectInputTypeName,
                    { node ->
                        ConnectInput.NodeConnectInput.Augmentation.generateContainerConnectInputIT(
                            node,
                            ctx
                        )
                    }, RelationFieldBaseAugmentation::generateFieldConnectIT
                )
                    ?.let { fields += inputValue(Constants.CONNECT_FIELD, it.asType()) }

                CreateInput.Augmentation.addEdgePropertyCreateInputField(
                    rel,
                    fields,
                    ctx,
                    required = { it.hasRequiredFields })

                ConnectWhere.Augmentation.generateConnectWhereIT(interfaze, ctx)
                    ?.let { fields += inputValue(Constants.WHERE, it.asType()) }
            }
        }
    }

    class NodeConnectFieldInputs(items: List<NodeConnectFieldInput>) : ConnectFieldInput,
        InputListWrapper<NodeConnectFieldInput>(items) {

        companion object {
            fun create(node: Node, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::NodeConnectFieldInputs,
                { NodeConnectFieldInput(node, relationshipProperties, it.toDict()) }
            )
        }
    }

    class InterfaceConnectFieldInputs(items: List<InterfaceConnectFieldInput>) : ConnectFieldInput,
        InputListWrapper<InterfaceConnectFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::InterfaceConnectFieldInputs,
                { InterfaceConnectFieldInput(interfaze, relationshipProperties, it.toDict()) }
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
            onUnion = { UnionConnectFieldInput(it, field.properties, value.toDict()) }
        )
    }
}
