package org.neo4j.graphql.schema.model.inputs.update

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationBaseField
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
                    node.relationBaseFields,
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
                    RelationFieldBaseAugmentation::generateFieldUpdateIT
                ) { fields ->
                    ScalarProperties.Companion.Augmentation
                        .addScalarFields(fields, interfaze.scalarFields, true, ctx)
                    if (fields.isEmpty()) {
                        fields += ctx.emptyInputField()
                    }
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

        fun getEdgePropertyUpdateInputIT(relationField: RelationBaseField, ctx: AugmentationContext) =
            ctx.getEdgeInputField(relationField, { it.namings.edgeUpdateInputTypeName }) {
                generateContainerUpdateIT(
                    it.namings.edgeUpdateInputTypeName,
                    emptyList(),
                    it.properties?.fields ?: emptyList(),
                    ctx = ctx
                )
            }


        internal fun generateContainerUpdateIT(
            name: String,
            relationFields: List<RelationBaseField>,
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

