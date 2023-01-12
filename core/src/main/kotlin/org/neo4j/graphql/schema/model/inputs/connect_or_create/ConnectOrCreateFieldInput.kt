package org.neo4j.graphql.schema.model.inputs.connect_or_create

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asRequiredType
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
import org.neo4j.graphql.schema.model.inputs.create.CreateInput
import org.neo4j.graphql.toDict

sealed interface ConnectOrCreateFieldInput {

    class NodeConnectOrCreateFieldInput(
        node: Node,
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) {
        val where = data.nestedDict(Constants.WHERE)
            ?.let { ConnectOrCreateWhere(node, it) }

        val onCreate = data.nestedDict(Constants.ON_CREATE_FIELD)
            ?.let { ConnectOrCreateFieldInputOnCreate(node, relationshipProperties, it) }


        object Augmentation : AugmentationBase {
            fun generateFieldConnectOrCreateIT(
                rel: RelationField,
                prefix: String,
                node: Node,
                ctx: AugmentationContext
            ): String? {
                if (node.uniqueFields.isEmpty()) {
                    return null
                }
                return ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.ConnectOrCreateFieldInput}") { fields, name ->

                    ConnectOrCreateWhere.Augmentation
                        .generateConnectOrCreateWhereIT(node, ctx)
                        ?.let { fields += inputValue(Constants.WHERE, it.asRequiredType()) }

                    generateNodeOnCreateIT(rel, "${name}${Constants.InputTypeSuffix.OnCreate}", node, ctx)
                        ?.let { fields += inputValue(Constants.ON_CREATE_FIELD, it.asRequiredType()) }
                }
            }

            private fun generateNodeOnCreateIT(rel: RelationField, name: String, node: Node, ctx: AugmentationContext) =
                ctx.getOrCreateInputObjectType(name) { fields, _ ->

                    generateNodeOnCreateInputIT(node, ctx)
                        ?.let { fields += inputValue(Constants.NODE_FIELD, it.asRequiredType()) }

                    CreateInput.Augmentation
                        .addEdgePropertyCreateInputField(rel.properties, fields, ctx,
                            required = { it.hasRequiredNonGeneratedFields }
                        )
                }

            private fun generateNodeOnCreateInputIT(node: Node, ctx: AugmentationContext) =

                ctx.getOrCreateInputObjectType(node.name + Constants.InputTypeSuffix.OnCreateInput) { fields, _ ->

                    ScalarProperties.Companion.Augmentation
                        .addScalarFields(fields, node.name, node.scalarFields, false, ctx)
                }

        }
    }

    class NodeConnectOrCreateFieldInputs(items: List<NodeConnectOrCreateFieldInput>) : ConnectOrCreateFieldInput,
        InputListWrapper<NodeConnectOrCreateFieldInput>(items) {

        companion object {
            fun create(node: Node, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::NodeConnectOrCreateFieldInputs,
                { NodeConnectOrCreateFieldInput(node, relationshipProperties, it.toDict()) }
            )
        }
    }

    class UnionConnectOrCreateFieldInput(
        union: Union,
        val relationshipProperties: RelationshipProperties?,
        data: Dict
    ) : ConnectOrCreateFieldInput,
        PerNodeInput<NodeConnectOrCreateFieldInputs>(
            union,
            data,
            { node, value -> NodeConnectOrCreateFieldInputs.create(node, relationshipProperties, value.toDict()) }
        )

    class ConnectOrCreateFieldInputOnCreate(node: Node, relationProps: RelationshipProperties?, data: Dict) {

        val node = data.nestedDict(Constants.NODE_FIELD)
            ?.let { ScalarProperties.create(it, node) }

        val edge = relationProps
            ?.let { props ->
                data.nestedDict(Constants.EDGE_FIELD)
                    ?.let { ScalarProperties.create(it, props) }
            }
    }

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeConnectOrCreateFieldInputs.create(it, field.properties, value) },
            onInterface = { error("cannot connect to interface") },
            onUnion = { UnionConnectOrCreateFieldInput(it, field.properties, value.toDict()) }
        )
    }
}
