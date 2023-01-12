package org.neo4j.graphql.schema.model.inputs.connect

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.PerNodeInput
import org.neo4j.graphql.schema.model.inputs.PerNodeInput.Companion.getCommonFields
import org.neo4j.graphql.schema.model.inputs.RelationFieldsInput
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation
import org.neo4j.graphql.wrapList

sealed class ConnectInput private constructor(
    implementingType: ImplementingType,
    data: Dict
) : RelationFieldsInput<ConnectFieldInput>(
    implementingType,
    data,
    { field, value -> ConnectFieldInput.create(field, value) }
) {

    class NodeConnectInput(node: Node, data: Dict) : ConnectInput(node, data) {

        object Augmentation : AugmentationBase {
            fun generateContainerConnectInputIT(node: Node, ctx: AugmentationContext) =
                ctx.getOrCreateRelationInputObjectType(
                    node.name,
                    Constants.InputTypeSuffix.ConnectInput,
                    node.relationFields,
                    RelationFieldBaseAugmentation::generateFieldConnectIT,
                )
        }
    }

    class InterfaceConnectInput(interfaze: Interface, data: Dict) : ConnectInput(interfaze, data) {

        val on = data[Constants.ON]?.let { on ->
            PerNodeInput(
                interfaze,
                Dict(on),
                { node, value ->
                    value.wrapList().map {
                        NodeConnectInput(
                            node,
                            Dict(it)
                        )
                    }
                }
            )
        }

        fun getCommonFields(implementation: Node) = on.getCommonFields(implementation, data, ::NodeConnectInput)
    }

    companion object {
        fun create(implementingType: ImplementingType, anyData: Any) = when (implementingType) {
            is Node -> NodeConnectInput(implementingType, Dict(anyData))
            is Interface -> InterfaceConnectInput(implementingType, Dict(anyData))
        }

        fun create(relationField: RelationField, anyData: Any): ConnectInput = relationField.extractOnTarget(
            onImplementingType = { create(it, anyData) },
            onUnion = { error("cannot create for a union type of field ${relationField.getOwnerName()}.${relationField.fieldName}") }
        )
    }
}

