package org.neo4j.graphql.domain.directives

import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class SubscriptionDirective(
    val events: Set<SubscriptionEventType>,
) {

    enum class SubscriptionEventType { CREATED, UPDATED, DELETED, RELATIONSHIP_CREATED, RELATIONSHIP_DELETED }

    val created get() = events.contains(SubscriptionEventType.CREATED)
    val updated get() = events.contains(SubscriptionEventType.UPDATED)
    val deleted get() = events.contains(SubscriptionEventType.DELETED)
    val relationshipCreated get() = events.contains(SubscriptionEventType.RELATIONSHIP_CREATED)
    val relationshipDeleted get() = events.contains(SubscriptionEventType.RELATIONSHIP_DELETED)
    val isSubscribable get() = events.isNotEmpty()

    companion object {
        const val NAME = "subscription"
        fun create(directive: Directive): SubscriptionDirective {
            directive.validateName(NAME)
            return SubscriptionDirective(directive.readArgument(SubscriptionDirective::events) { arrayValue ->
                (arrayValue as ArrayValue).values.map {
                    SubscriptionEventType.valueOf((it as EnumValue).name)
                }
            }?.toSet() ?: SubscriptionEventType.values().toSet())
        }
    }
}

