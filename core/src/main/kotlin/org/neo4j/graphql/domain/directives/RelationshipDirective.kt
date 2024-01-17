package org.neo4j.graphql.domain.directives

import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

data class RelationshipDirective(
    val direction: RelationField.Direction,
    val type: String,
    val properties: String?,
    val queryDirection: RelationField.QueryDirection,
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
        const val NAME = "relationship"
        fun create(directive: Directive): RelationshipDirective {
            directive.validateName(NAME)

            val direction =
                directive.readRequiredArgument(RelationshipDirective::direction) { RelationField.Direction.valueOf((it as EnumValue).name) }

            val type = directive.readRequiredArgument(RelationshipDirective::type)

            val properties = directive.readArgument(RelationshipDirective::properties)

            val queryDirection =
                directive.readArgument(RelationshipDirective::queryDirection) { RelationField.QueryDirection.valueOf((it as EnumValue).name) }
                    ?: RelationField.QueryDirection.DEFAULT_DIRECTED

            val nestedOperations =
                directive.readArgument(RelationshipDirective::nestedOperations) { arrayValue ->
                    (arrayValue as ArrayValue).values.map {
                        RelationshipNestedOperations.valueOf((it as EnumValue).name)
                    }
                }?.toSet() ?: RelationshipNestedOperations.values().toSet()

            val aggregate = directive.readArgument(RelationshipDirective::aggregate) ?: true

            return RelationshipDirective(direction, type, properties, queryDirection, nestedOperations, aggregate)
        }
    }
}


