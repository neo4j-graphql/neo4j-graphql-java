package org.neo4j.graphql.domain.inputs.connect_or_create

import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.RelationFieldsInput
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation

class ConnectOrCreateInput private constructor(
    node: Node,
    data: Dict
) : RelationFieldsInput<ConnectOrCreateFieldInput>(
    node,
    data,
    { field, value -> ConnectOrCreateFieldInput.create(field, value) }
) {
    companion object {
        fun create(node: Node, anyData: Any) = ConnectOrCreateInput(node, Dict(anyData))
    }

    object Augmentation {
        fun generateContainerConnectOrCreateInputIT(node: Node, ctx: AugmentationContext) =
            ctx.getOrCreateRelationInputObjectType(
                node.name,
                Constants.InputTypeSuffix.ConnectOrCreateInput,
                node.relationFields,
                RelationFieldBaseAugmentation::generateFieldConnectOrCreateIT
            )
    }
}
