package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.domain.directives.Annotations

class InterfaceEntityOperations(
    name: String,
    annotations: Annotations
) : ImplementingTypeOperations(name, annotations) {

    val implementationEnumTypename get() = "${name}Implementation"
    val whereOnImplementationsWhereInputTypeName get() = "${name}ImplementationsWhere"
    val whereOnImplementationsUpdateInputTypeName get() = "${name}ImplementationsUpdateInput"
    val whereOnImplementationsDeleteInputTypeName get() = "${name}ImplementationsDeleteInput"
    val whereOnImplementationsConnectInputTypeName get() = "${name}ImplementationsConnectInput"
    val whereOnImplementationsDisconnectInputTypeName get() = "${name}ImplementationsDisconnectInput"
    val implementationsSubscriptionWhereInputTypeName get() = "${name}ImplementationsSubscriptionWhere"
}
