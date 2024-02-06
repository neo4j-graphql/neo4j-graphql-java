package org.neo4j.graphql.schema.model.inputs.update

import graphql.language.InputValueDefinition
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.PerNodeInput
import org.neo4j.graphql.schema.model.inputs.PerNodeInput.Companion.getCommonFields
import org.neo4j.graphql.schema.model.inputs.RelationFieldsInput
import org.neo4j.graphql.schema.model.inputs.ScalarProperties
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation
import org.neo4j.graphql.toDict

sealed class UpdateInput private constructor(implementingType: ImplementingType, data: Dict) :
    RelationFieldsInput<UpdateFieldInput>(
        implementingType,
        data,
        { field, value -> UpdateFieldInput.create(field, value) }
    ) {


    val properties by lazy { ScalarProperties.create(data, implementingType) }

    class NodeUpdateInput(node: Node, data: Dict) : UpdateInput(node, data) {

        object Augmentation : AugmentationBase {
            fun generateContainerUpdateIT(node: Node, ctx: AugmentationContext) = UpdateInput.Augmentation
                .generateContainerUpdateIT(
                    node.namings.updateInputTypeName,
                    node.fields.filterIsInstance<RelationField>(),
                    node.scalarFields,
                    enforceFields = true,
                    ctx
                )
        }
    }

    class InterfaceUpdateInput(interfaze: Interface, data: Dict) : UpdateInput(interfaze, data) {

        val on = data.nestedDict(Constants.ON)
            ?.let { PerNodeInput(interfaze, it, { node: Node, value: Any -> NodeUpdateInput(node, value.toDict()) }) }

        fun getCommonFields(implementation: Node) = on.getCommonFields(implementation, data, ::NodeUpdateInput)

        object Augmentation : AugmentationBase {

            fun generateUpdateInputIT(interfaze: Interface, ctx: AugmentationContext) =
                ctx.addInterfaceField(
                    interfaze,
                    interfaze.namings.updateInputTypeName,
                    interfaze.namings.whereOnImplementationsUpdateInputTypeName,
                    { node -> NodeUpdateInput.Augmentation.generateContainerUpdateIT(node, ctx) },
                    RelationFieldBaseAugmentation::generateFieldUpdateIT,
                    asList = false
                ) {
                    val fields = mutableListOf<InputValueDefinition>()

                    ScalarProperties.Companion.Augmentation
                        .addScalarFields(fields, interfaze.scalarFields, true, ctx)

                    fields
                }
        }
    }

    companion object {
        fun create(implementingType: ImplementingType, data: Dict) = when (implementingType) {
            is Node -> NodeUpdateInput(implementingType, data)
            is Interface -> InterfaceUpdateInput(implementingType, data)
        }
    }

    object Augmentation : AugmentationBase {

        fun addEdgePropertyUpdateInputField(
            relationField: RelationField,
            fields: MutableList<InputValueDefinition>,
            ctx: AugmentationContext
        ) = relationField.properties?.let { props ->
            generateContainerUpdateIT(
                relationField.namings.edgeUpdateInputTypeName,
                emptyList(),
                props.fields,
                ctx = ctx
            )
                ?.let { fields += inputValue(Constants.EDGE_FIELD, it.asType()) }
        }

        internal fun generateContainerUpdateIT(
            name: String,
            relationFields: List<RelationField>,
            scalarFields: List<ScalarField>,
            enforceFields: Boolean = false,
            ctx: AugmentationContext
        ) = ctx.getOrCreateRelationInputObjectType(
            name,
            relationFields,
            RelationFieldBaseAugmentation::generateFieldUpdateIT,
            scalarFields = scalarFields,
            update = true,
            enforceFields = enforceFields,
            condition = { it.annotations.settable?.onUpdate != false }
        )
    }
}

