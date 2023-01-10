package org.neo4j.graphql.domain.inputs.connect

import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.WhereInput

class ConnectWhere(type: ImplementingType, data: Dict) {
    val node = data[Constants.NODE_FIELD]?.let { WhereInput.create(type, it) }

    object Augmentation {
        fun generateConnectWhereIT(
            implementingType: ImplementingType,
            ctx: AugmentationContext
        ) =
            ctx.getOrCreateInputObjectType("${implementingType.name}${Constants.InputTypeSuffix.ConnectWhere}") { fields, _ ->

                when (implementingType) {

                    is Interface -> WhereInput.InterfaceWhereInput.Augmentation.generateFieldWhereIT(
                        implementingType,
                        ctx
                    )

                    is Node -> WhereInput.NodeWhereInput.Augmentation.generateWhereIT(implementingType, ctx)

                }?.let { whereType ->
                    fields += ctx.inputValue(Constants.NODE_FIELD, whereType.asRequiredType())
                }
            }
    }
}
