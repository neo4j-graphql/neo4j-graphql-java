package org.neo4j.graphql.schema.model.inputs

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.BaseField

class AuthorizationWhere(node: Node, val jwtDefinition: FieldContainer<BaseField>?, data: Dict) :
    NestedWhere<AuthorizationWhere>(data,
        { nestedData -> AuthorizationWhere(node, jwtDefinition, nestedData) }) {

    val node = data.nestedDict(Constants.NODE_FIELD)?.let { WhereInput.NodeWhereInput(node, it) }

    val jwt = data.nestedDict(Constants.JWT)
        ?.let { jwtData -> jwtDefinition?.let { JwtWhereInput(it, jwtData) } }

}
