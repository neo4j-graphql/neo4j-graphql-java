package org.neo4j.graphql.schema.model.inputs.create

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.RelationFieldsInput
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation
import org.neo4j.graphql.toDict

class RelationInput private constructor(
    node: Node,
    data: Dict
) : RelationFieldsInput<RelationFieldInput>(
    node,
    data,
    { field, value -> RelationFieldInput.create(field, value) }) {

    companion object {
        fun create(node: Node, anyData: Any) = RelationInput(node, anyData.toDict())
    }

    object Augmentation : AugmentationBase {

        fun generateContainerRelationCreateInputIT(node: Node, ctx: AugmentationContext) =
            ctx.getOrCreateRelationInputObjectType(
                node.name,
                Constants.InputTypeSuffix.RelationInput,
                node.relationFields,
                RelationFieldBaseAugmentation::generateFieldRelationCreateIT,
                condition = { it.annotations.relationship?.isCreateAllowed != false },
            )
    }
}

