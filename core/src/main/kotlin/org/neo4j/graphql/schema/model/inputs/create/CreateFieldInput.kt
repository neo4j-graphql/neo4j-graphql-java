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
import org.neo4j.graphql.schema.model.inputs.create.RelationFieldInput.InterfaceCreateFieldInput
import org.neo4j.graphql.schema.model.inputs.create.RelationFieldInput.NodeCreateCreateFieldInput
import org.neo4j.graphql.toDict
import org.neo4j.graphql.wrapType

sealed interface CreateFieldInput {

    sealed class ImplementingTypeFieldInput {
        abstract val create: List<RelationFieldInput.ImplementingTypeCreateFieldInput>?
        abstract val connect: List<ImplementingTypeConnectFieldInput>?
    }

    class NodeFieldInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        CreateFieldInput,
        ImplementingTypeFieldInput() {

        override val create = data
            .nestedDictList(Constants.CREATE_FIELD)
            .map { NodeCreateCreateFieldInput(node, relationshipProperties, it.toDict()) }
            .takeIf { it.isNotEmpty() }

        override val connect = data.nestedObject(Constants.CONNECT_FIELD)
            ?.let { NodeConnectFieldInputs.create(node, relationshipProperties, it) }

        val connectOrCreate = data.nestedObject(Constants.CONNECT_OR_CREATE_FIELD)
            ?.let { ConnectOrCreateFieldInput.NodeConnectOrCreateFieldInputs.create(node, relationshipProperties, it) }


        object Augmentation : AugmentationBase {
            fun generateFieldNodeFieldInputIT(
                rel: RelationField,
                prefix: String,
                node: Node,
                ctx: AugmentationContext
            ): String? {
                if (!rel.shouldGenerateFieldInputType(node)) {
                    return null
                }
                return ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.FieldInput}") { fields, _ ->
                    if (rel.annotations.relationship?.isCreateAllowed != false) {
                        NodeCreateCreateFieldInput.Augmentation
                            .generateFieldCreateFieldInputIT(rel, prefix, node, ctx)
                            ?.let { fields += inputValue(Constants.CREATE_FIELD, it.wrapType(rel)) }
                    }

                    if (rel.annotations.relationship?.isConnectAllowed != false) {
                        NodeConnectFieldInput.Augmentation
                            .generateFieldConnectFieldInputIT(rel, prefix, node, ctx)
                            ?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType(rel)) }
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

    class InterfaceFieldInput(interfaze: Interface, relationshipProperties: RelationshipProperties?, data: Dict) :
        CreateFieldInput,
        ImplementingTypeFieldInput() {

        override val create = data.nestedDictList(Constants.CREATE_FIELD)
            .map { InterfaceCreateFieldInput(interfaze, relationshipProperties, it.toDict()) }
            .takeIf { it.isNotEmpty() }

        override val connect = data.nestedObject(Constants.CONNECT_FIELD)
            ?.let { InterfaceConnectFieldInputs.create(interfaze, relationshipProperties, it) }

        object Augmentation : AugmentationBase {
            fun generateFieldCreateIT(
                rel: RelationField,
                prefix: String,
                interfaze: Interface,
                ctx: AugmentationContext
            ): String? {
                if (!rel.shouldGenerateFieldInputType(interfaze)) {
                    return null
                }
                return ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.FieldInput}") { fields, _ ->

                    if (rel.annotations.relationship?.isCreateAllowed != false) {
                        InterfaceCreateFieldInput.Augmentation
                            .generateFieldRelationCreateIT(rel, prefix, interfaze, ctx)
                            ?.let {
                                fields += inputValue(Constants.CREATE_FIELD, it.wrapType(rel))
                            }
                    }

                    if (rel.annotations.relationship?.isConnectAllowed != false) {
                        InterfaceConnectFieldInput.Augmentation
                            .generateFieldConnectIT(rel, prefix, interfaze, ctx)
                            ?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType(rel)) }
                    }
                }
            }

        }
    }


    class UnionFieldInput(union: Union, relationshipProperties: RelationshipProperties?, data: Dict) : CreateFieldInput,
        PerNodeInput<NodeFieldInput>(
            union,
            data,
            { node, value -> NodeFieldInput(node, relationshipProperties, value.toDict()) })

    companion object {
        fun create(field: RelationField, value: Dict) = field.extractOnTarget(
            onNode = { NodeFieldInput(it, field.properties, value) },
            onInterface = { InterfaceFieldInput(it, field.properties, value) },
            onUnion = { UnionFieldInput(it, field.properties, value) }
        )
    }
}
