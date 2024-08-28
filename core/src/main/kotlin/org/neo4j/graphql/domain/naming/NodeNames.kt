package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.directives.NodeAnnotations

class NodeNames(
    name: String,
    annotations: NodeAnnotations
) : ImplementingTypeNames(name, annotations) {

    fun getFullTextIndexInputTypeName(indexName: String) = "${name}${indexName.capitalize()}Fulltext"
    fun getFullTextIndexQueryFieldName(indexName: String) = "${plural}Fulltext${indexName.capitalize()}"

    val onCreateInputTypeName get() = "${name}OnCreateInput"
    val relationshipsSubscriptionWhereInputTypeName get() = "${name}RelationshipsSubscriptionWhere"
    val relationshipCreatedSubscriptionWhereInputTypeName get() = "${name}RelationshipCreatedSubscriptionWhere"
    val relationshipDeletedSubscriptionWhereInputTypeName get() = "${name}RelationshipDeletedSubscriptionWhere"
    val connectionFieldTypename get() = "${name}Connection"
    val relationshipFieldTypename get() = "${name}Edge"

    val fulltextTypeNames get() = FulltextTypeNames()

    inner class FulltextTypeNames {
        val result get() = "${pascalCaseSingular}FulltextResult"
        val where get() = "${pascalCaseSingular}FulltextWhere"
        val sort get() = "${pascalCaseSingular}FulltextSort"
    }

    val mutationResponseTypeNames get() = MutationResponseTypeNames()

    inner class MutationResponseTypeNames {
        val create get() = "Create${pascalCasePlural}MutationResponse"
        val update get() = "Update${pascalCasePlural}MutationResponse"
    }

    val subscriptionEventTypeNames get() = SubscriptionEventTypeNames()

    inner class SubscriptionEventTypeNames {
        val create get() = "${pascalCaseSingular}CreatedEvent"
        val update get() = "${pascalCaseSingular}UpdatedEvent"
        val delete get() = "${pascalCaseSingular}DeletedEvent"
        val createRelationship get() = "${pascalCaseSingular}RelationshipCreatedEvent"
        val deleteRelationship get() = "${pascalCaseSingular}RelationshipDeletedEvent"
    }

    val subscriptionEventPayloadFieldNames get() = SubscriptionEventPayloadFieldNames()

    inner class SubscriptionEventPayloadFieldNames {
        val create get() = "created${pascalCaseSingular}"
        val update get() = "updated${pascalCaseSingular}"
        val delete get() = "deleted${pascalCaseSingular}"
        val createRelationship get() = singular
        val deleteRelationship get() = singular
    }

    val createMutationArgumentNames get() = CreateMutationArgumentNames()

    inner class CreateMutationArgumentNames {
        val where get() = createInputTypeName
    }

    val connectOrCreateWhereInputFieldNames get() = ConnectOrCreateWhereInputFieldNames()

    inner class ConnectOrCreateWhereInputFieldNames {
        val node get() = uniqueWhereInputTypeName
    }
}
