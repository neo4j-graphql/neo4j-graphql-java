package org.neo4j.graphql.domain.inputs.delete

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.PerNodeInput.Companion.getCommonFields
import org.neo4j.graphql.domain.inputs.RelationFieldsInput
import org.neo4j.graphql.wrapList

sealed class DeleteInput private constructor(implementingType: ImplementingType, data: Dict) :
    RelationFieldsInput<DeleteFieldInput>(
        implementingType,
        data,
        { field, value -> DeleteFieldInput.create(field, value) }
    ) {


    class NodeDeleteInput(node: Node, data: Dict) : DeleteInput(node, data)

    class InterfaceDeleteInput(interfaze: Interface, data: Dict) : DeleteInput(interfaze, data) {

        val on = data[Constants.ON]?.let {
            PerNodeInput(
                interfaze,
                Dict(it),
                { node: Node, value: Any -> value.wrapList().map { NodeDeleteInput(node, Dict(it)) } }
            )
        }

        fun getCommonFields(implementation: Node) = on.getCommonFields(implementation, data, ::NodeDeleteInput)
    }

    companion object {
        fun create(implementingType: ImplementingType, anyData: Any) = when (implementingType) {
            is Node -> NodeDeleteInput(implementingType, Dict(anyData))
            is Interface -> InterfaceDeleteInput(implementingType, Dict(anyData))
        }
    }
}

