package org.neo4j.graphql.domain.inputs.delete

import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.InputListWrapper
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.connection.ConnectionWhere
import org.neo4j.graphql.domain.inputs.connection.ConnectionWhere.InterfaceConnectionWhere
import org.neo4j.graphql.domain.inputs.connection.ConnectionWhere.NodeConnectionWhere
import org.neo4j.graphql.schema.InterfaceAugmentation
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation

sealed interface DeleteFieldInput {
    sealed interface ImplementingTypeDeleteFieldInput {
        val where: ConnectionWhere.ImplementingTypeConnectionWhere<*>?
        val delete: DeleteInput?
    }

    class NodeDeleteFieldInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeDeleteFieldInput {

        override val where =
            data[Constants.WHERE]?.let { NodeConnectionWhere(node, relationshipProperties, Dict(it)) }

        override val delete = data[Constants.DELETE_FIELD]?.let { DeleteInput.NodeDeleteInput(node, Dict(it)) }

        object Augmentation {
            fun generateFieldDeleteFieldInputIT(
                rel: RelationField,
                prefix: String,
                node: Node,
                ctx: AugmentationContext
            ) =

                ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.DeleteFieldInput}") { fields, _ ->

                    NodeConnectionWhere.Augmentation
                        .generateFieldConnectionWhereIT(rel, prefix, node, ctx)
                        ?.let { fields += ctx.inputValue(Constants.WHERE, it.asType()) }

                    DeleteInput.NodeDeleteInput.Augmentation
                        .generateContainerDeleteInputIT(node, ctx)
                        ?.let { fields += ctx.inputValue(Constants.DELETE_FIELD, it.asType()) }
                }
        }
    }

    class InterfaceDeleteFieldInput(interfaze: Interface, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeDeleteFieldInput {

        override val where = data[Constants.WHERE]
            ?.let { InterfaceConnectionWhere(interfaze, relationshipProperties, Dict(it)) }

        override val delete =
            data[Constants.DELETE_FIELD]?.let { DeleteInput.InterfaceDeleteInput(interfaze, Dict(it)) }

        val on = data[Constants.ON]?.let {
            PerNodeInput(
                interfaze,
                Dict(it),
                { node: Node, value: Any -> NodeDeleteFieldInputs.create(node, relationshipProperties, value) }
            )
        }

        object Augmentation {
            fun generateFieldDeleteIT(
                rel: RelationField,
                prefix: String,
                interfaze: Interface,
                ctx: AugmentationContext
            ) = ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.DeleteFieldInput}") { fields, _ ->

                InterfaceAugmentation(interfaze, ctx)
                    .addInterfaceField(
                        Constants.InputTypeSuffix.DeleteInput,
                        { node -> DeleteInput.NodeDeleteInput.Augmentation.generateContainerDeleteInputIT(node, ctx) },
                        RelationFieldBaseAugmentation::generateFieldDeleteIT
                    )
                    ?.let { fields += ctx.inputValue(Constants.DELETE_FIELD, it.asType()) }

                InterfaceConnectionWhere.Augmentation.generateFieldConnectionWhereIT(rel, interfaze, ctx)
                    ?.let { fields += ctx.inputValue(Constants.WHERE, it.asType()) }
            }

        }
    }

    class NodeDeleteFieldInputs(items: List<NodeDeleteFieldInput>) : DeleteFieldInput,
        InputListWrapper<NodeDeleteFieldInput>(items) {

        companion object {
            fun create(node: Node, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::NodeDeleteFieldInputs,
                { NodeDeleteFieldInput(node, relationshipProperties, Dict(it)) }
            )
        }
    }

    class InterfaceDeleteFieldInputs(items: List<InterfaceDeleteFieldInput>) : DeleteFieldInput,
        InputListWrapper<InterfaceDeleteFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::InterfaceDeleteFieldInputs,
                { InterfaceDeleteFieldInput(interfaze, relationshipProperties, Dict(it)) }
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
            onUnion = { UnionDeleteFieldInput(it, field.properties, Dict(value)) }
        )
    }
}
