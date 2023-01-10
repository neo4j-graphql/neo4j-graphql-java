package org.neo4j.graphql.domain.inputs.create

import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.InputListWrapper
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.ScalarProperties
import org.neo4j.graphql.schema.InterfaceAugmentation

sealed interface RelationFieldInput {
    sealed class ImplementingTypeCreateFieldInput(implementingType: ImplementingType, data: Dict) {
        val edge = data[Constants.EDGE_FIELD]?.let { ScalarProperties.create(data, implementingType) }
    }

    class NodeCreateCreateFieldInput(node: Node, value: Dict) : ImplementingTypeCreateFieldInput(node, value) {
        val node = CreateInput.create(node, value)

        companion object {
            fun create(node: Node, value: Any?): NodeCreateCreateFieldInput {
                return NodeCreateCreateFieldInput(node, Dict(value))
            }
        }

        object Augmentation {
            fun generateFieldCreateFieldInputIT(
                rel: RelationField,
                prefix: String,
                node: Node,
                ctx: AugmentationContext
            ) =
                ctx.getOrCreateInputObjectType(prefix + Constants.InputTypeSuffix.CreateFieldInput) { fields, _ ->

                    CreateInput.Augmentation
                        .generateContainerCreateInputIT(node, ctx)
                        ?.let { fields += ctx.inputValue(Constants.NODE_FIELD, it.asRequiredType()) }

                    CreateInput.Augmentation
                        .addEdgePropertyCreateInputField(
                            rel.properties, fields, ctx,
                            required = { it.hasRequiredNonGeneratedFields })
                }
        }
    }

    class InterfaceCreateFieldInput(interfaze: Interface, data: Dict) :
        ImplementingTypeCreateFieldInput(interfaze, data) {

        val node = PerNodeInput(interfaze, Dict(data), { node: Node, value: Any -> CreateInput.create(node, value) })

        companion object {
            fun create(interfaze: Interface, value: Any): InterfaceCreateFieldInput {
                return InterfaceCreateFieldInput(interfaze, Dict(value))
            }
        }

        object Augmentation {
            fun generateFieldRelationCreateIT(
                rel: RelationField,
                prefix: String,
                interfaze: Interface,
                ctx: AugmentationContext
            ) =
                ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.CreateFieldInput}") { fields, _ ->

                    generateCreateInputIT(interfaze, ctx)?.let {
                        fields += ctx.inputValue(Constants.NODE_FIELD, it.asRequiredType())
                    }

                    CreateInput.Augmentation
                        .addEdgePropertyCreateInputField(rel.properties, fields, ctx, required = { true })
                }

            private fun generateCreateInputIT(interfaze: Interface, ctx: AugmentationContext) =
                InterfaceAugmentation(interfaze, ctx)
                    .generateImplementationDelegate(
                        Constants.InputTypeSuffix.CreateInput,
                        asList = false,
                        { node -> CreateInput.Augmentation.generateContainerCreateInputIT(node, ctx) }
                    ) {
                        // TODO REVIEW Darrell
                        //    interface-relationships_--nested-relationships.adoc
                        //  vs
                        //   interface-relationships_--nested-interface-relationships.adoc
//                interfaze.relationFields.mapNotNull { r ->
//                    getTypeFromRelationField(interfaze.name, r, RelationAugmentation::addCreateType)
//                        ?.let { inputValue(r.fieldName, it.asType()) }
//                }
                        emptyList()
                    }
        }
    }

    class NodeCreateCreateFieldInputs(items: List<NodeCreateCreateFieldInput>) : RelationFieldInput,
        InputListWrapper<NodeCreateCreateFieldInput>(items) {

        companion object {
            fun create(node: Node, value: Any?) = create(
                value,
                ::NodeCreateCreateFieldInputs,
                { NodeCreateCreateFieldInput(node, Dict(it)) }
            )
        }
    }

    class InterfaceCreateFieldInputs(items: List<InterfaceCreateFieldInput>) : RelationFieldInput,
        InputListWrapper<InterfaceCreateFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, value: Any?) = create(
                value,
                ::InterfaceCreateFieldInputs,
                { InterfaceCreateFieldInput(interfaze, Dict(it)) }
            )
        }
    }

    class UnionFieldInput(union: Union, data: Dict) : RelationFieldInput,
        PerNodeInput<NodeCreateCreateFieldInputs>(
            union,
            data,
            { node, value -> NodeCreateCreateFieldInputs.create(node, value) }
        )

    companion object {
        fun create(field: RelationField, value: Any) =
            field.extractOnTarget(
                onNode = { NodeCreateCreateFieldInputs.create(it, value) },
                onInterface = { InterfaceCreateFieldInputs.create(it, value) },
                onUnion = { UnionFieldInput(it, Dict(value)) }
            )

    }

}


