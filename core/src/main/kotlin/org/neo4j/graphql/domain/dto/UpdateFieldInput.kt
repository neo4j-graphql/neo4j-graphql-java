package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.nestedObject
import org.neo4j.graphql.wrapList

class UpdateFieldInput(
    val connect: ConnectFieldInput.NodeConnectFieldInputs? = null,
    val create: CreateInput? = null,
    val connectOrCreate: List<ConnectOrCreateInput>? = null,
    val delete: List<DeleteFieldInput>? = null,
    val disconnect: DisconnectFieldInput.NodeDisconnectFieldInputs? = null,
    val update: UpdateConnectionInput? = null,
    val where: ConnectionWhere? = null,
) {
    companion object {
        fun create(
            field: RelationField,
            value: Any?,
            targetType: ImplementingType = field.getImplementingType()
                ?: TODO("for union fields the desired target must be passed")
        ): UpdateFieldInput {
            val connect = value.nestedObject(Constants.CONNECT_FIELD)
                ?.wrapList()
                ?.let { ConnectFieldInput.NodeConnectFieldInputs.create(field, targetType, it)}

            val create = value.nestedObject(Constants.CREATE_FIELD)
                ?.let { CreateInput.create(targetType, it) }

            val connectOrCreate = value.nestedObject(Constants.CONNECT_OR_CREATE_FIELD)
                ?.wrapList()
                ?.map { ConnectOrCreateInput.create(field, targetType, it) }

            val delete = value.nestedObject(Constants.DELETE_FIELD)
                ?.wrapList()
                ?.map { DeleteFieldInput.create(field, it ) }

            val disconnect = value.nestedObject(Constants.DISCONNECT_FIELD)
                ?.wrapList()
                ?.let { DisconnectFieldInput.NodeDisconnectFieldInputs.create(field, targetType, it) }

            val update = value.nestedObject(Constants.UPDATE_FIELD)
                ?.let { UpdateConnectionInput.create(field, it) }

            val where = value.nestedObject(Constants.WHERE)
                ?.let { ConnectionWhere.create(field, it) }

            return UpdateFieldInput(connect, create, connectOrCreate, delete, disconnect, update, where)
        }
    }
}
