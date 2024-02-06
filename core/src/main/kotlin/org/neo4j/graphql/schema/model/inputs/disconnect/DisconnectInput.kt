package org.neo4j.graphql.schema.model.inputs.disconnect

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.PerNodeInput
import org.neo4j.graphql.schema.model.inputs.PerNodeInput.Companion.getCommonFields
import org.neo4j.graphql.schema.model.inputs.RelationFieldsInput
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation
import org.neo4j.graphql.toDict
import org.neo4j.graphql.wrapList

sealed class DisconnectInput private constructor(implementingType: ImplementingType, data: Dict) :
    RelationFieldsInput<DisconnectFieldInput>(
        implementingType,
        data,
        { field, value -> DisconnectFieldInput.create(field, value) }
    ) {

    class NodeDisconnectInput(node: Node, data: Dict) : DisconnectInput(node, data) {
        object Augmentation : AugmentationBase {
            fun generateContainerDisconnectInputIT(node: Node, ctx: AugmentationContext) =
                ctx.getOrCreateRelationInputObjectType(
                    node.namings.disconnectInputTypeName,
                    node.relationFields,
                    RelationFieldBaseAugmentation::generateFieldDisconnectIT,
                    condition = { it.annotations.relationship?.isDisconnectAllowed != false },
                )
        }
    }

    class InterfaceDisconnectInput(interfaze: Interface, data: Dict) : DisconnectInput(interfaze, data) {

        val on = data.nestedDict(Constants.ON)?.let { on ->
            PerNodeInput(
                interfaze,
                on,
                { node: Node, value: Any -> value.wrapList().toDict().map { NodeDisconnectInput(node, it) } })
        }

        fun getCommonFields(implementation: Node) = on.getCommonFields(implementation, data, ::NodeDisconnectInput)
    }

    companion object {
        fun create(implementingType: ImplementingType, data: Dict) = when (implementingType) {
            is Node -> NodeDisconnectInput(implementingType, data)
            is Interface -> InterfaceDisconnectInput(implementingType, data)
        }
    }
}

