package org.neo4j.graphql.schema.model.inputs.delete

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

sealed interface DeleteFieldInput {
    sealed interface ImplementingTypeDeleteFieldInput {
        val where: ConnectionWhere.ImplementingTypeConnectionWhere<*>?
        val delete: DeleteInput?
    }

    class NodeDeleteFieldInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeDeleteFieldInput {

        override val where = data.nestedDict(Constants.WHERE)
            ?.let { NodeConnectionWhere(node, relationshipProperties, it) }

        override val delete = data.nestedDict(Constants.DELETE_FIELD)
            ?.let { DeleteInput.NodeDeleteInput(node, it) }

        object Augmentation : AugmentationBase {
            fun generateFieldDeleteFieldInputIT(
                rel: RelationField,
                node: Node,
                ctx: AugmentationContext
            ) =

                ctx.getOrCreateInputObjectType(rel.namings.getDeleteFieldInputTypeName(node)) { fields, _ ->

                    NodeConnectionWhere.Augmentation
                        .generateFieldConnectionWhereIT(rel, node, ctx)
                        ?.let { fields += inputValue(Constants.WHERE, it.asType()) }

                    DeleteInput.NodeDeleteInput.Augmentation
                        .generateContainerDeleteInputIT(node, ctx)
                        ?.let { fields += inputValue(Constants.DELETE_FIELD, it.asType()) }
                }
        }
    }

    class InterfaceDeleteFieldInput(interfaze: Interface, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeDeleteFieldInput {

        override val where = data.nestedDict(Constants.WHERE)
            ?.let { InterfaceConnectionWhere(interfaze, relationshipProperties, it) }

        override val delete = data.nestedDict(Constants.DELETE_FIELD)
            ?.let { DeleteInput.InterfaceDeleteInput(interfaze, it) }

        val on = data.nestedDict(Constants.ON)
            ?.let {
                PerNodeInput(
                    interfaze,
                    it,
                    { node: Node, value: Any -> NodeDeleteFieldInputs.create(node, relationshipProperties, value) })
            }

        object Augmentation : AugmentationBase {
            fun generateFieldDeleteIT(
                rel: RelationField,
                interfaze: Interface,
                ctx: AugmentationContext
            ) = ctx.getOrCreateInputObjectType(rel.namings.getDeleteFieldInputTypeName(interfaze)) { fields, _ ->

                ctx.addInterfaceField(
                    interfaze,
                    interfaze.namings.deleteInputTypeName,
                    interfaze.namings.whereOnImplementationsDeleteInputTypeName,
                    { node -> DeleteInput.NodeDeleteInput.Augmentation.generateContainerDeleteInputIT(node, ctx) },
                    RelationFieldBaseAugmentation::generateFieldDeleteIT
                )
                    ?.let { fields += inputValue(Constants.DELETE_FIELD, it.asType()) }

                InterfaceConnectionWhere.Augmentation.generateFieldConnectionWhereIT(rel, interfaze, ctx)
                    ?.let { fields += inputValue(Constants.WHERE, it.asType()) }
            }

        }
    }

    class NodeDeleteFieldInputs(items: List<NodeDeleteFieldInput>) : DeleteFieldInput,
        InputListWrapper<NodeDeleteFieldInput>(items) {

        companion object {
            fun create(node: Node, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::NodeDeleteFieldInputs,
                { NodeDeleteFieldInput(node, relationshipProperties, it.toDict()) }
            )
        }
    }

    class InterfaceDeleteFieldInputs(items: List<InterfaceDeleteFieldInput>) : DeleteFieldInput,
        InputListWrapper<InterfaceDeleteFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::InterfaceDeleteFieldInputs,
                { InterfaceDeleteFieldInput(interfaze, relationshipProperties, it.toDict()) }
            )
        }
    }

    class UnionDeleteFieldInput(union: Union, relationshipProperties: RelationshipProperties?, data: Dict) :
        DeleteFieldInput,
        PerNodeInput<NodeDeleteFieldInputs>(
            union,
            data,
            { node, value -> NodeDeleteFieldInputs.create(node, relationshipProperties, value) }
        )

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeDeleteFieldInputs.create(it, field.properties, value) },
            onInterface = { InterfaceDeleteFieldInputs.create(it, field.properties, value) },
            onUnion = { UnionDeleteFieldInput(it, field.properties, value.toDict()) }
        )
    }
}
