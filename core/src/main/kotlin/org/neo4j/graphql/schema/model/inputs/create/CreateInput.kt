package org.neo4j.graphql.schema.model.inputs.create

import graphql.language.InputValueDefinition
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.fields.RelationField
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

        fun addEdgePropertyCreateInputField(
            relationField: RelationField,
            fields: MutableList<InputValueDefinition>,
            ctx: AugmentationContext,
            required: (RelationshipProperties) -> Boolean = { false }
        ) =
            relationField.properties?.let { props ->
                generateContainerCreateInputIT(
                    relationField.operations.createInputTypeName,
                    emptyList(),
                    props.fields,
                    ctx
                )?.let {
                    fields += inputValue(Constants.EDGE_FIELD, it.asType(required(props)))
                }
            }

        fun generateContainerCreateInputIT(node: Node, ctx: AugmentationContext) = generateContainerCreateInputIT(
            node.operations.createInputTypeName,
            node.fields.filterIsInstance<RelationField>(),
            node.scalarFields.filter { it.isCreateInputField() },
            ctx,
            enforceFields = true,
        )

        private fun generateContainerCreateInputIT(
            name: String,
            relationFields: List<RelationField>,
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

