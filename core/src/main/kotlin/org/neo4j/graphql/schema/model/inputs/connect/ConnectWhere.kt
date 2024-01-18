package org.neo4j.graphql.schema.model.inputs.connect

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.WhereInput

class ConnectWhere(type: ImplementingType, data: Dict) {
    val node = data.nestedDict(Constants.NODE_FIELD)?.let { WhereInput.create(type, it) }

    object Augmentation : AugmentationBase {
        fun generateConnectWhereIT(
            implementingType: ImplementingType,
            ctx: AugmentationContext
        ) =
            ctx.getOrCreateInputObjectType(implementingType.operations.connectWhereInputTypeName) { fields, _ ->

                when (implementingType) {

                    is Interface -> WhereInput.InterfaceWhereInput.Augmentation.generateFieldWhereIT(
                        implementingType,
                        ctx
                    )

                    is Node -> WhereInput.NodeWhereInput.Augmentation.generateWhereIT(implementingType, ctx)

                }?.let { whereType ->
                    fields += inputValue(Constants.NODE_FIELD, whereType.asRequiredType())
                }
            }
    }
}
