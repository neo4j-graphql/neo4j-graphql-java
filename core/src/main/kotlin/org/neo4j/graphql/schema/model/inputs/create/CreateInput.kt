package org.neo4j.graphql.schema.model.inputs.create

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.RelationFieldsInput
import org.neo4j.graphql.schema.model.inputs.ScalarProperties
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation
import org.neo4j.graphql.toDict

class CreateInput private constructor(
    val node: Node,
    data: Dict
) : RelationFieldsInput<CreateFieldInput>(
    node,
    data,
    { field, value -> CreateFieldInput.create(field, value.toDict()) }
) {

    val properties by lazy { ScalarProperties.create(data, node) }

    companion object {
        fun create(node: Node, data: Dict) = CreateInput(node, data)
    }

    object Augmentation : AugmentationBase {

        fun getEdgePropertyCreateInputIT(
            relationField: RelationBaseField,
            ctx: AugmentationContext,
            required: (RelationshipProperties) -> Boolean
        ) =
            ctx.getEdgeInputField(relationField, { it.namings.createInputTypeName }, required) {
                generateContainerCreateInputIT(
                    it.namings.createInputTypeName,
                    emptyList(),
                    it.properties?.fields ?: emptyList(),
                    ctx
                )
            }

        fun generateContainerCreateInputIT(node: Node, ctx: AugmentationContext) = generateContainerCreateInputIT(
            node.namings.createInputTypeName,
            node.relationBaseFields,
            node.scalarFields.filter { it.isCreateInputField() },
            ctx,
            enforceFields = true,
        )

        private fun generateContainerCreateInputIT(
            name: String,
            relationFields: List<RelationBaseField>,
            scalarFields: List<ScalarField>,
            ctx: AugmentationContext,
            enforceFields: Boolean = false,
        ) =
            ctx.getOrCreateRelationInputObjectType(
                name,
                relationFields,
                RelationFieldBaseAugmentation::generateFieldCreateIT,
                wrapList = false,
                scalarFields,
                enforceFields = enforceFields,
                condition = { it.annotations.settable?.onCreate != false }
            )
    }
}

