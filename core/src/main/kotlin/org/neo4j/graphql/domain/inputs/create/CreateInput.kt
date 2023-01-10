package org.neo4j.graphql.domain.inputs.create

import graphql.language.InputValueDefinition
import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.RelationFieldsInput
import org.neo4j.graphql.domain.inputs.ScalarProperties
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation

class CreateInput private constructor(
    node: Node,
    data: Dict
) : RelationFieldsInput<CreateFieldInput>(
    node,
    data,
    { field, value -> CreateFieldInput.create(field, value) }
) {

    val properties by lazy { ScalarProperties.create(data, node) }

    companion object {
        fun create(node: Node, anyData: Any) = CreateInput(node, Dict(anyData))
    }

    object Augmentation {

        fun addEdgePropertyCreateInputField(
            properties: RelationshipProperties?,
            fields: MutableList<InputValueDefinition>,
            ctx: AugmentationContext,
            required: (RelationshipProperties) -> Boolean = { false }
        ) =
            properties?.let { props ->
                generateContainerCreateInputIT(props.interfaceName, emptyList(), props.fields, ctx)?.let {
                    fields += ctx.inputValue(Constants.EDGE_FIELD, it.asType(required(props)))
                }
            }

        fun generateContainerCreateInputIT(node: Node, ctx: AugmentationContext) = generateContainerCreateInputIT(
            node.name,
            node.fields.filterIsInstance<RelationField>(),
            node.scalarFields,
            ctx,
            enforceFields = true,
        )

        private fun generateContainerCreateInputIT(
            sourceName: String,
            relationFields: List<RelationField>,
            scalarFields: List<ScalarField>,
            ctx: AugmentationContext,
            enforceFields: Boolean = false,
        ) =
            ctx.getOrCreateRelationInputObjectType(
                sourceName,
                Constants.InputTypeSuffix.CreateInput,
                relationFields,
                RelationFieldBaseAugmentation::generateFieldCreateIT,
                wrapList = false,
                scalarFields,
                enforceFields = enforceFields,
            )
    }
}

