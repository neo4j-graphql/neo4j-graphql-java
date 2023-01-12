package org.neo4j.graphql.schema.model.inputs.create

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.PerNodeInput
import org.neo4j.graphql.schema.model.inputs.connect.ConnectFieldInput.*
import org.neo4j.graphql.schema.model.inputs.connect_or_create.ConnectOrCreateFieldInput
import org.neo4j.graphql.wrapList
import org.neo4j.graphql.wrapType

sealed interface CreateFieldInput {

    sealed class ImplementingTypeFieldInput {
        abstract val create: List<RelationFieldInput.ImplementingTypeCreateFieldInput>?
        abstract val connect: List<ImplementingTypeConnectFieldInput>?
    }

    class NodeFieldInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        CreateFieldInput,
        ImplementingTypeFieldInput() {

        override val create = data[Constants.CREATE_FIELD]
            ?.wrapList()
            ?.map { RelationFieldInput.NodeCreateCreateFieldInput.create(node, it) }
            ?.takeIf { it.isNotEmpty() }

        override val connect = data[Constants.CONNECT_FIELD]
            ?.let { NodeConnectFieldInputs.create(node, relationshipProperties, it) }

        val connectOrCreate = data[Constants.CONNECT_OR_CREATE_FIELD]
            ?.let { ConnectOrCreateFieldInput.NodeConnectOrCreateFieldInputs.create(node, relationshipProperties, it) }


        object Augmentation : AugmentationBase {
            fun generateFieldNodeFieldInputIT(
                rel: RelationField,
                prefix: String,
                node: Node,
                ctx: AugmentationContext
            ) =
                ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.FieldInput}") { fields, _ ->
                    RelationFieldInput.NodeCreateCreateFieldInput.Augmentation
                        .generateFieldCreateFieldInputIT(rel, prefix, node, ctx)
                        ?.let { fields += inputValue(Constants.CREATE_FIELD, it.wrapType(rel)) }

                    NodeConnectFieldInput.Augmentation
                        .generateFieldConnectFieldInputIT(rel, prefix, node, ctx)
                        ?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType(rel)) }

                    ConnectOrCreateFieldInput.NodeConnectOrCreateFieldInput.Augmentation
                        .generateFieldConnectOrCreateIT(rel, prefix, node, ctx)
                        ?.let { fields += inputValue(Constants.CONNECT_OR_CREATE_FIELD, it.wrapType(rel)) }
                }
        }
    }

    class InterfaceFieldInput(interfaze: Interface, relationshipProperties: RelationshipProperties?, data: Dict) :
        CreateFieldInput,
        ImplementingTypeFieldInput() {
        override val create = data[Constants.CREATE_FIELD]
            ?.wrapList()
            ?.map { RelationFieldInput.InterfaceCreateFieldInput.create(interfaze, it) }
            ?.takeIf { it.isNotEmpty() }

        override val connect = data[Constants.CONNECT_FIELD]
            ?.let { InterfaceConnectFieldInputs.create(interfaze, relationshipProperties, it) }

        object Augmentation : AugmentationBase{
            fun generateFieldCreateIT(
                rel: RelationField,
                prefix: String,
                interfaze: Interface,
                ctx: AugmentationContext
            ) = ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.FieldInput}") { fields, _ ->

                RelationFieldInput.InterfaceCreateFieldInput.Augmentation
                    .generateFieldRelationCreateIT(rel, prefix, interfaze, ctx)
                    ?.let {
                        fields += inputValue(Constants.CREATE_FIELD, it.wrapType(rel))
                    }

                InterfaceConnectFieldInput.Augmentation
                    .generateFieldConnectIT(rel, prefix, interfaze, ctx)
                    ?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType(rel)) }
            }

        }
    }


    class UnionFieldInput(union: Union, relationshipProperties: RelationshipProperties?, data: Dict) : CreateFieldInput,
        PerNodeInput<NodeFieldInput>(
            union,
            data,
            { node, value -> NodeFieldInput(node, relationshipProperties, Dict(value)) })

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeFieldInput(it, field.properties, Dict(value)) },
            onInterface = { InterfaceFieldInput(it, field.properties, Dict(value)) },
            onUnion = { UnionFieldInput(it, field.properties, Dict(value)) }
        )
    }
}
