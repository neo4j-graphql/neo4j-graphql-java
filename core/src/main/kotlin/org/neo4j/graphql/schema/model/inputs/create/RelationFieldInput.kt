package org.neo4j.graphql.schema.model.inputs.create

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asRequiredType
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
import org.neo4j.graphql.toDict

sealed interface RelationFieldInput {
    sealed class ImplementingTypeCreateFieldInput(relationshipProperties: RelationshipProperties?, data: Dict) {
        val edge = data.nestedDict(Constants.EDGE_FIELD)
            ?.let { ScalarProperties.create(it, relationshipProperties) }
    }

    class NodeCreateCreateFieldInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeCreateFieldInput(relationshipProperties, data) {
        val node = data.nestedDict(Constants.NODE_FIELD)
            ?.let { CreateInput.create(node, it) }

        object Augmentation : AugmentationBase {
            fun generateFieldCreateFieldInputIT(
                rel: RelationField,
                node: Node,
                ctx: AugmentationContext
            ) =
                ctx.getOrCreateInputObjectType(rel.operations.getCreateFieldInputTypeName(node)) { fields, _ ->

                    CreateInput.Augmentation
                        .generateContainerCreateInputIT(node, ctx)
                        ?.let { fields += inputValue(Constants.NODE_FIELD, it.asRequiredType()) }

                    CreateInput.Augmentation
                        .addEdgePropertyCreateInputField(
                            rel, fields, ctx,
                            required = { it.hasRequiredNonGeneratedFields })
                }
        }
    }

    class InterfaceCreateFieldInput(interfaze: Interface, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeCreateFieldInput(relationshipProperties, data) {

        val node = data.nestedDict(Constants.NODE_FIELD)
            ?.let {
                PerNodeInput(
                    interfaze,
                    it,
                    { node: Node, value: Any -> CreateInput.create(node, value.toDict()) })
            }

        object Augmentation : AugmentationBase {
            fun generateFieldRelationCreateIT(
                rel: RelationField,
                interfaze: Interface,
                ctx: AugmentationContext,
                name: String = rel.operations.getCreateFieldInputTypeName(interfaze)
            ): String? {
                return ctx.getOrCreateInputObjectType(name) { fields, _ ->

                    generateCreateInputIT(interfaze, ctx)?.let {
                        fields += inputValue(Constants.NODE_FIELD, it.asRequiredType())
                    }

                    CreateInput.Augmentation
                        .addEdgePropertyCreateInputField(
                            rel,
                            fields,
                            ctx,
                            required = { it.hasRequiredNonGeneratedFields })
                }
            }

            private fun generateCreateInputIT(interfaze: Interface, ctx: AugmentationContext) =
                ctx.generateImplementationDelegate(
                    interfaze, interfaze.operations.createInputTypeName,
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
            fun create(node: Node, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::NodeCreateCreateFieldInputs,
                { NodeCreateCreateFieldInput(node, relationshipProperties, it.toDict()) }
            )
        }
    }

    class InterfaceCreateFieldInputs(items: List<InterfaceCreateFieldInput>) : RelationFieldInput,
        InputListWrapper<InterfaceCreateFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::InterfaceCreateFieldInputs,
                { InterfaceCreateFieldInput(interfaze, relationshipProperties, it.toDict()) }
            )
        }
    }

    class UnionFieldInput(union: Union, relationshipProperties: RelationshipProperties?, data: Dict) :
        RelationFieldInput,
        PerNodeInput<NodeCreateCreateFieldInputs>(
            union,
            data,
            { node, value -> NodeCreateCreateFieldInputs.create(node, relationshipProperties, value) }
        )

    companion object {
        fun create(field: RelationField, value: Any) =
            field.extractOnTarget(
                onNode = { NodeCreateCreateFieldInputs.create(it, field.properties, value) },
                onInterface = { InterfaceCreateFieldInputs.create(it, field.properties, value) },
                onUnion = { UnionFieldInput(it, field.properties, value.toDict()) }
            )

    }

}


