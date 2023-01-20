package org.neo4j.graphql.schema.model.inputs.create

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.InputListWrapper
import org.neo4j.graphql.schema.model.inputs.PerNodeInput
import org.neo4j.graphql.schema.model.inputs.ScalarProperties
import org.neo4j.graphql.toDict

sealed interface RelationFieldInput {
    sealed class ImplementingTypeCreateFieldInput(implementingType: ImplementingType, data: Dict) {
        val edge = data.nestedDict(Constants.EDGE_FIELD)
            ?.let { ScalarProperties.create(it, implementingType) }
    }

    class NodeCreateCreateFieldInput(node: Node, data: Dict) : ImplementingTypeCreateFieldInput(node, data) {
        val node = data.nestedDict(Constants.NODE_FIELD)
            ?.let { CreateInput.create(node, it) }

        companion object {
            fun create(node: Node, value: Any?): NodeCreateCreateFieldInput {
                return NodeCreateCreateFieldInput(node, value.toDict())
            }
        }

        object Augmentation : AugmentationBase {
            fun generateFieldCreateFieldInputIT(
                rel: RelationField,
                prefix: String,
                node: Node,
                ctx: AugmentationContext
            ) =
                ctx.getOrCreateInputObjectType(prefix + Constants.InputTypeSuffix.CreateFieldInput) { fields, _ ->

                    CreateInput.Augmentation
                        .generateContainerCreateInputIT(node, ctx)
                        ?.let { fields += inputValue(Constants.NODE_FIELD, it.asRequiredType()) }

                    CreateInput.Augmentation
                        .addEdgePropertyCreateInputField(
                            rel.properties, fields, ctx,
                            required = { it.hasRequiredNonGeneratedFields })
                }
        }
    }

    class InterfaceCreateFieldInput(interfaze: Interface, data: Dict) :
        ImplementingTypeCreateFieldInput(interfaze, data) {

        val node = data.nestedDict(Constants.NODE_FIELD)
            ?.let {
                PerNodeInput(
                    interfaze,
                    it,
                    { node: Node, value: Any -> CreateInput.create(node, value.toDict()) })
            }

        companion object {
            fun create(interfaze: Interface, value: Any): InterfaceCreateFieldInput {
                return InterfaceCreateFieldInput(interfaze, value.toDict())
            }
        }

        object Augmentation : AugmentationBase {
            fun generateFieldRelationCreateIT(
                rel: RelationField,
                prefix: String,
                interfaze: Interface,
                ctx: AugmentationContext
            ) =
                ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.CreateFieldInput}") { fields, _ ->

                    generateCreateInputIT(interfaze, ctx)?.let {
                        fields += inputValue(Constants.NODE_FIELD, it.asRequiredType())
                    }

                    CreateInput.Augmentation
                        .addEdgePropertyCreateInputField(rel.properties, fields, ctx, required = { true })
                }

            private fun generateCreateInputIT(interfaze: Interface, ctx: AugmentationContext) =
                ctx.generateImplementationDelegate(interfaze, Constants.InputTypeSuffix.CreateInput,
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
                { NodeCreateCreateFieldInput(node, it.toDict()) }
            )
        }
    }

    class InterfaceCreateFieldInputs(items: List<InterfaceCreateFieldInput>) : RelationFieldInput,
        InputListWrapper<InterfaceCreateFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, value: Any?) = create(
                value,
                ::InterfaceCreateFieldInputs,
                { InterfaceCreateFieldInput(interfaze, it.toDict()) }
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
                onUnion = { UnionFieldInput(it, value.toDict()) }
            )

    }

}


