package org.neo4j.graphql.domain.directives

import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.readArgument

sealed class RelationshipBaseDirective(
    val nestedOperations: Set<RelationshipNestedOperations>,
    val aggregate: Boolean,
) {

    enum class RelationshipNestedOperations { CREATE, UPDATE, DELETE, CONNECT, DISCONNECT, CONNECT_OR_CREATE }

    val isOnlyConnectOrCreate get() = isConnectOrCreateAllowed && nestedOperations.size == 1
    val isCreateAllowed get() = nestedOperations.contains(RelationshipNestedOperations.CREATE)
    val isUpdateAllowed get() = nestedOperations.contains(RelationshipNestedOperations.UPDATE)
    val isDeleteAllowed get() = nestedOperations.contains(RelationshipNestedOperations.DELETE)
    val isConnectAllowed get() = nestedOperations.contains(RelationshipNestedOperations.CONNECT)
    val isDisconnectAllowed get() = nestedOperations.contains(RelationshipNestedOperations.DISCONNECT)
    val isConnectOrCreateAllowed get() = nestedOperations.contains(RelationshipNestedOperations.CONNECT_OR_CREATE)


    companion object {
        fun extractNestedOperations(directive: Directive): Set<RelationshipNestedOperations> {
            val nestedOperations =
                directive.readArgument(RelationshipBaseDirective::nestedOperations) { arrayValue ->
                    (arrayValue as ArrayValue).values.map {
                        RelationshipNestedOperations.valueOf((it as EnumValue).name)
                    }
                }?.toSet() ?: RelationshipNestedOperations.values().toSet()
            return nestedOperations
        }
    }
}
