package org.neo4j.graphql.schema.model.inputs.connect_or_create

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.RelationFieldsInput
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation
import org.neo4j.graphql.toDict

class ConnectOrCreateInput private constructor(
    node: Node,
    data: Dict
) : RelationFieldsInput<ConnectOrCreateFieldInput>(
    node,
    data,
    { field, value -> ConnectOrCreateFieldInput.create(field, value) }
) {
    companion object {
        fun create(node: Node, anyData: Any) = ConnectOrCreateInput(node, anyData.toDict())
    }

    object Augmentation : AugmentationBase {
        fun generateContainerConnectOrCreateInputIT(node: Node, ctx: AugmentationContext) =
            ctx.getOrCreateRelationInputObjectType(
                node.namings.connectOrCreateInputTypeName,
                node.relationBaseFields,
                RelationFieldBaseAugmentation::generateFieldConnectOrCreateIT,
                condition = { it.annotations.relationshipBaseDirective?.isConnectOrCreateAllowed != false },
            )
    }
}
