package org.neo4j.graphql.schema.model.inputs.delete

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

sealed class DeleteInput private constructor(implementingType: ImplementingType, data: Dict) :
    RelationFieldsInput<DeleteFieldInput>(
        implementingType,
        data,
        { field, value -> DeleteFieldInput.create(field, value) }
    ) {


    class NodeDeleteInput(node: Node, data: Dict) : DeleteInput(node, data) {

        object Augmentation : AugmentationBase {
            fun generateContainerDeleteInputIT(node: Node, ctx: AugmentationContext) =
                ctx.getOrCreateRelationInputObjectType(
                    node.namings.deleteInputTypeName,
                    node.relationFields,
                    RelationFieldBaseAugmentation::generateFieldDeleteIT,
                    condition = { it.annotations.relationship?.isDeleteAllowed != false },
                )
        }
    }

    class InterfaceDeleteInput(interfaze: Interface, data: Dict) : DeleteInput(interfaze, data) {

        val on = data.nestedDict(Constants.ON)?.let { on ->
            PerNodeInput(
                interfaze,
                on,
                { node: Node, value: Any -> value.wrapList().toDict().map { NodeDeleteInput(node, it) } })
        }

        fun getCommonFields(implementation: Node) = on.getCommonFields(implementation, data, ::NodeDeleteInput)
    }

    companion object {
        fun create(implementingType: ImplementingType, data: Dict) = when (implementingType) {
            is Node -> NodeDeleteInput(implementingType, data)
            is Interface -> InterfaceDeleteInput(implementingType, data)
        }
    }
}

