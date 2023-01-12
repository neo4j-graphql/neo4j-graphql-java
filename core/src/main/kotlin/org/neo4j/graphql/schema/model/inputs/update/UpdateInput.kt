package org.neo4j.graphql.schema.model.inputs.update

import graphql.language.InputValueDefinition
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
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

sealed class UpdateInput private constructor(implementingType: ImplementingType, data: Dict) :
    RelationFieldsInput<UpdateFieldInput>(
        implementingType,
        data,
        { field, value -> UpdateFieldInput.create(field, value) }
    ) {


    val properties by lazy { ScalarProperties.create(data, implementingType) }

    class NodeUpdateInput(node: Node, data: Dict) : UpdateInput(node, data) {

        object Augmentation : AugmentationBase{
            fun generateContainerUpdateIT(node: Node, ctx: AugmentationContext) = UpdateInput.Augmentation
                .generateContainerUpdateIT(
                    node.name,
                    node.fields.filterIsInstance<RelationField>(),
                    node.scalarFields,
                    enforceFields = true,
                    ctx
                )
        }
    }

    class InterfaceUpdateInput(interfaze: Interface, data: Dict) : UpdateInput(interfaze, data) {

        val on = data[Constants.ON]?.let {
            PerNodeInput(interfaze, Dict(it), { node: Node, value: Any -> NodeUpdateInput(node, Dict(value)) })
        }

        fun getCommonFields(implementation: Node) = on.getCommonFields(implementation, data, ::NodeUpdateInput)

        object Augmentation : AugmentationBase{

            fun generateUpdateInputIT(interfaze: Interface, ctx: AugmentationContext) =
                ctx.addInterfaceField(
                    interfaze,
                    Constants.InputTypeSuffix.UpdateInput,
                    { node -> NodeUpdateInput.Augmentation.generateContainerUpdateIT(node, ctx) },
                    RelationFieldBaseAugmentation::generateFieldUpdateIT,
                    asList = false
                ) {
                    val fields = mutableListOf<InputValueDefinition>()

                    ScalarProperties.Companion.Augmentation
                        .addScalarFields(fields, interfaze.name, interfaze.scalarFields, true, ctx)

                    fields
                }
        }
    }

    companion object {
        fun create(implementingType: ImplementingType, anyData: Any) = when (implementingType) {
            is Node -> NodeUpdateInput(implementingType, Dict(anyData))
            is Interface -> InterfaceUpdateInput(implementingType, Dict(anyData))
        }
    }

    object Augmentation : AugmentationBase {

        fun addEdgePropertyUpdateInputField(
            properties: RelationshipProperties?,
            fields: MutableList<InputValueDefinition>,
            ctx: AugmentationContext
        ) = properties?.let { props ->
            generateContainerUpdateIT(props.interfaceName, emptyList(), props.fields, ctx = ctx)
                ?.let { fields += inputValue(Constants.EDGE_FIELD, it.asType()) }
        }

        internal fun generateContainerUpdateIT(
            sourceName: String,
            relationFields: List<RelationField>,
            scalarFields: List<ScalarField>,
            enforceFields: Boolean = false,
            ctx: AugmentationContext
        ) = ctx.getOrCreateRelationInputObjectType(
            sourceName,
            Constants.InputTypeSuffix.UpdateInput,
            relationFields,
            RelationFieldBaseAugmentation::generateFieldUpdateIT,
            scalarFields = scalarFields,
            update = true,
            enforceFields = enforceFields,
        )
    }
}

