package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.domain.directives.Annotations

abstract class ImplementingTypeOperations(
    name: String,
    annotations: Annotations
) : BaseNames(name, annotations) {

    val uniqueWhereInputTypeName get() = "${name}UniqueWhere"
    val connectOrCreateWhereInputTypeName get() = "${name}ConnectOrCreateWhere"
    val connectWhereInputTypeName get() = "${name}ConnectWhere"
    val createInputTypeName get() = "${name}CreateInput"
    val updateInputTypeName get() = "${name}UpdateInput"
    val deleteInputTypeName get() = "${name}DeleteInput"
    val optionsInputTypeName get() = "${name}Options"
    val fullTextInputTypeName get() = "${name}Fulltext"
    val sortInputTypeName get() = "${name}Sort"
    val relationInputTypeName get() = "${name}RelationInput"
    val connectInputTypeName get() = "${name}ConnectInput"
    val disconnectInputTypeName get() = "${name}DisconnectInput"
    val connectOrCreateInputTypeName get() = "${name}ConnectOrCreateInput"
    val subscriptionWhereInputTypeName get() = "${name}SubscriptionWhere"
    override val rootTypeFieldNames get() = ImplementingTypeRootTypeFieldNames()

    open inner class ImplementingTypeRootTypeFieldNames : RootTypeFieldNames() {
        val create get() = "create${pascalCasePlural}"
        val update get() = "update${pascalCasePlural}"
        val delete get() = "delete${pascalCasePlural}"
        val aggregate get() = "${plural}Aggregate"
        val subscribe get() = SubscribeFieldNames()

        inner class SubscribeFieldNames {
            val created get() = "${singular}Created"
            val updated get() = "${singular}Updated"
            val deleted get() = "${singular}Deleted"
            val relationshipDeleted get() = "${singular}RelationshipDeleted"
            val relationshipCreated get() = "${singular}RelationshipCreated"
        }
    }

    val aggregateTypeNames get() = AggregateTypeNames()

    inner class AggregateTypeNames {
        val selection get() = "${name}AggregateSelection"
        val input get() = "${name}AggregateSelectionInput"
    }

    val updateMutationArgumentNames get() = UpdateMutationArgumentNames()

    inner class UpdateMutationArgumentNames {
        val connect get() = connectInputTypeName
        val disconnect get() = disconnectInputTypeName
        val create get() = relationInputTypeName
        val update get() = updateInputTypeName
        val delete get() = deleteInputTypeName
        val connectOrCreate get() = connectOrCreateInputTypeName
        val where get() = whereInputTypeName
    }

}
