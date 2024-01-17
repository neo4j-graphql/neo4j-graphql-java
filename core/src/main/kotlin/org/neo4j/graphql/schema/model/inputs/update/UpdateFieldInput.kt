package org.neo4j.graphql.schema.model.inputs.update

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
import org.neo4j.graphql.schema.model.inputs.ScalarProperties
import org.neo4j.graphql.schema.model.inputs.connect.ConnectFieldInput.*
import org.neo4j.graphql.schema.model.inputs.connect_or_create.ConnectOrCreateFieldInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere.InterfaceConnectionWhere
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere.NodeConnectionWhere
import org.neo4j.graphql.schema.model.inputs.create.RelationFieldInput
import org.neo4j.graphql.schema.model.inputs.delete.DeleteFieldInput
import org.neo4j.graphql.schema.model.inputs.disconnect.DisconnectFieldInput
import org.neo4j.graphql.toDict
import org.neo4j.graphql.wrapType

sealed interface UpdateFieldInput {

    sealed class ImplementingTypeUpdateFieldInput {
        abstract val where: ConnectionWhere.ImplementingTypeConnectionWhere<*>?
        abstract val update: ImplementingTypeUpdateConnectionInput?
        abstract val connect: List<ImplementingTypeConnectFieldInput>?
        abstract val disconnect: List<DisconnectFieldInput.ImplementingTypeDisconnectFieldInput>?
        abstract val create: RelationFieldInput?
        abstract val delete: DeleteFieldInput?
    }

    class NodeUpdateFieldInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeUpdateFieldInput() {
        override val where = data.nestedDict(Constants.WHERE)
            ?.let { NodeConnectionWhere(node, relationshipProperties, it) }

        override val update = data.nestedDict(Constants.UPDATE_FIELD)
            ?.let { NodeUpdateConnectionInput(node, relationshipProperties, it) }

        override val connect = data.nestedObject(Constants.CONNECT_FIELD)
            ?.let { NodeConnectFieldInputs.create(node, relationshipProperties, it) }

        override val disconnect = data.nestedObject(Constants.DISCONNECT_FIELD)
            ?.let { DisconnectFieldInput.NodeDisconnectFieldInputs.create(node, relationshipProperties, it) }

        override val create = data.nestedObject(Constants.CREATE_FIELD)
            ?.let { RelationFieldInput.NodeCreateCreateFieldInputs.create(node, relationshipProperties, it) }

        override val delete = data.nestedObject(Constants.DELETE_FIELD)
            ?.let { DeleteFieldInput.NodeDeleteFieldInputs.create(node, relationshipProperties, it) }

        val connectOrCreate = data.nestedObject(Constants.CONNECT_OR_CREATE_FIELD)
            ?.let { ConnectOrCreateFieldInput.NodeConnectOrCreateFieldInputs.create(node, relationshipProperties, it) }

        object Augmentation : AugmentationBase {

            fun generateFieldUpdateFieldInputIT(
                rel: RelationField,
                prefix: String,
                node: Node,
                ctx: AugmentationContext
            ): String? {
                if (!rel.shouldGenerateUpdateFieldInputType(node)) {
                    return null
                }
                return ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.UpdateFieldInput}") { fields, _ ->

                    NodeConnectionWhere.Augmentation
                        .generateFieldConnectionWhereIT(rel, prefix, node, ctx)
                        ?.let { fields += inputValue(Constants.WHERE, it.asType()) }

                    if (rel.annotations.relationship?.isUpdateAllowed != false) {
                        NodeUpdateConnectionInput.Augmentation
                            .generateFieldUpdateConnectionInputIT(rel, prefix, node, ctx)
                            ?.let { fields += inputValue(Constants.UPDATE_FIELD, it.asType()) }
                    }

                    if (rel.annotations.relationship?.isConnectAllowed != false) {
                        NodeConnectFieldInput.Augmentation
                            .generateFieldConnectFieldInputIT(rel, prefix, node, ctx)
                            ?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType(rel)) }
                    }
                    if (rel.annotations.relationship?.isDisconnectAllowed != false) {
                        DisconnectFieldInput.NodeDisconnectFieldInput.Augmentation
                            .generateFieldDisconnectFieldInputIT(rel, prefix, node, ctx)
                            ?.let { fields += inputValue(Constants.DISCONNECT_FIELD, it.wrapType(rel)) }
                    }

                    if (rel.annotations.relationship?.isCreateAllowed != false) {
                        RelationFieldInput.NodeCreateCreateFieldInput.Augmentation
                            .generateFieldCreateFieldInputIT(rel, prefix, node, ctx)
                            ?.let { fields += inputValue(Constants.CREATE_FIELD, it.wrapType(rel)) }
                    }

                    if (rel.annotations.relationship?.isDeleteAllowed != false) {
                        DeleteFieldInput.NodeDeleteFieldInput.Augmentation
                            .generateFieldDeleteFieldInputIT(rel, prefix, node, ctx)
                            ?.let { fields += inputValue(Constants.DELETE_FIELD, it.wrapType(rel)) }
                    }

                    if (rel.annotations.relationship?.isConnectOrCreateAllowed != false) {
                        ConnectOrCreateFieldInput.NodeConnectOrCreateFieldInput.Augmentation
                            .generateFieldConnectOrCreateIT(rel, prefix, node, ctx)
                            ?.let { fields += inputValue(Constants.CONNECT_OR_CREATE_FIELD, it.wrapType(rel)) }
                    }
                }
            }
        }
    }

    class InterfaceUpdateFieldInput(interfaze: Interface, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeUpdateFieldInput() {

        override val update = data.nestedDict(Constants.UPDATE_FIELD)
            ?.let { InterfaceUpdateConnectionInput(interfaze, relationshipProperties, it) }

        override val connect = data.nestedObject(Constants.CONNECT_FIELD)
            ?.let { InterfaceConnectFieldInputs.create(interfaze, relationshipProperties, it) }

        override val disconnect = data.nestedObject(Constants.DISCONNECT_FIELD)
            ?.let { DisconnectFieldInput.InterfaceDisconnectFieldInputs.create(interfaze, relationshipProperties, it) }

        override val create = data.nestedObject(Constants.CREATE_FIELD)
            ?.let { RelationFieldInput.InterfaceCreateFieldInputs.create(interfaze, relationshipProperties, it) }

        override val delete = data.nestedObject(Constants.DELETE_FIELD)
            ?.let { DeleteFieldInput.InterfaceDeleteFieldInputs.create(interfaze, relationshipProperties, it) }

        override val where = data.nestedDict(Constants.WHERE)
            ?.let { InterfaceConnectionWhere(interfaze, relationshipProperties, it) }

        object Augmentation : AugmentationBase {

            fun generateFieldUpdateIT(
                rel: RelationField,
                prefix: String,
                interfaze: Interface,
                ctx: AugmentationContext
            ): String? {
                if (!rel.shouldGenerateUpdateFieldInputType(interfaze)) {
                    return null
                }
                return ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.UpdateFieldInput}") { fields, _ ->

                    InterfaceConnectionWhere.Augmentation
                        .generateFieldConnectionWhereIT(rel, interfaze, ctx)
                        ?.let { fields += inputValue(Constants.WHERE, it.asType()) }

                    if (rel.annotations.relationship?.isUpdateAllowed != false) {
                        InterfaceUpdateConnectionInput.Augmentation
                            .generateFieldUpdateConnectionInputIT(rel, prefix, interfaze, ctx)
                            ?.let { fields += inputValue(Constants.UPDATE_FIELD, it.asType()) }
                    }

                    if (rel.annotations.relationship?.isConnectAllowed != false) {
                        InterfaceConnectFieldInput.Augmentation
                            .generateFieldConnectIT(rel, prefix, interfaze, ctx)
                            ?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType(rel)) }
                    }

                    if (rel.annotations.relationship?.isDisconnectAllowed != false) {
                        DisconnectFieldInput.InterfaceDisconnectFieldInput.Augmentation
                            .generateFieldDisconnectIT(rel, prefix, interfaze, ctx)
                            ?.let { fields += inputValue(Constants.DISCONNECT_FIELD, it.wrapType(rel)) }
                    }

                    if (rel.annotations.relationship?.isCreateAllowed != false) {
                        RelationFieldInput.InterfaceCreateFieldInput.Augmentation
                            .generateFieldRelationCreateIT(rel, prefix, interfaze, ctx)
                            ?.let { fields += inputValue(Constants.CREATE_FIELD, it.wrapType(rel)) }
                    }

                    if (rel.annotations.relationship?.isDeleteAllowed != false) {
                        DeleteFieldInput.InterfaceDeleteFieldInput.Augmentation
                            .generateFieldDeleteIT(rel, prefix, interfaze, ctx)
                            ?.let { fields += inputValue(Constants.DELETE_FIELD, it.wrapType(rel)) }
                    }
                }
            }
        }

    }

    sealed class ImplementingTypeUpdateConnectionInput(relationshipProperties: RelationshipProperties?, data: Dict) {

        val edge = data.nestedDict(Constants.EDGE_FIELD)
            ?.let { ScalarProperties.create(it, relationshipProperties) }

        abstract val node: UpdateInput?
    }

    class InterfaceUpdateConnectionInput(
        interfaze: Interface,
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) :
        ImplementingTypeUpdateConnectionInput(relationshipProperties, data) {

        override val node = data.nestedDict(Constants.NODE_FIELD)
            ?.let { UpdateInput.InterfaceUpdateInput(interfaze, it) }

        object Augmentation : AugmentationBase {

            fun generateFieldUpdateConnectionInputIT(
                rel: RelationField,
                prefix: String,
                interfaze: Interface,
                ctx: AugmentationContext
            ) =
                ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.UpdateConnectionInput}") { fields, _ ->

                    UpdateInput.InterfaceUpdateInput.Augmentation
                        .generateUpdateInputIT(interfaze, ctx)
                        ?.let { fields += inputValue(Constants.NODE_FIELD, it.asType()) }

                    UpdateInput.Augmentation
                        .addEdgePropertyUpdateInputField(rel.properties, fields, ctx)
                }
        }
    }

    class NodeUpdateConnectionInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeUpdateConnectionInput(relationshipProperties, data) {

        override val node = data.nestedDict(Constants.NODE_FIELD)
            ?.let { UpdateInput.NodeUpdateInput(node, it) }

        object Augmentation : AugmentationBase {

            fun generateFieldUpdateConnectionInputIT(
                rel: RelationField,
                prefix: String,
                node: Node,
                ctx: AugmentationContext
            ) =
                ctx.getOrCreateInputObjectType(prefix + Constants.InputTypeSuffix.UpdateConnectionInput) { fields, _ ->

                    UpdateInput.NodeUpdateInput.Augmentation.generateContainerUpdateIT(node, ctx)
                        ?.let { fields += inputValue(Constants.NODE_FIELD, it.asType()) }

                    UpdateInput.Augmentation
                        .addEdgePropertyUpdateInputField(rel.properties, fields, ctx)

                }
        }
    }

    class NodeUpdateFieldInputs(items: List<NodeUpdateFieldInput>) : UpdateFieldInput,
        InputListWrapper<NodeUpdateFieldInput>(items) {

        companion object {
            fun create(node: Node, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::NodeUpdateFieldInputs,
                { NodeUpdateFieldInput(node, relationshipProperties, it.toDict()) }
            )
        }
    }

    class InterfaceUpdateFieldInputs(items: List<InterfaceUpdateFieldInput>) : UpdateFieldInput,
        InputListWrapper<InterfaceUpdateFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::InterfaceUpdateFieldInputs,
                { InterfaceUpdateFieldInput(interfaze, relationshipProperties, it.toDict()) }
            )
        }
    }

    class UnionUpdateFieldInput(union: Union, relationshipProperties: RelationshipProperties?, data: Dict) :
        UpdateFieldInput,
        PerNodeInput<NodeUpdateFieldInputs>(
            union,
            data,
            { node, value -> NodeUpdateFieldInputs.create(node, relationshipProperties, value) }
        )

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeUpdateFieldInputs.create(it, field.properties, value) },
            onInterface = { InterfaceUpdateFieldInputs.create(it, field.properties, value) },
            onUnion = { UnionUpdateFieldInput(it, field.properties, value.toDict()) }
        )
    }
}
