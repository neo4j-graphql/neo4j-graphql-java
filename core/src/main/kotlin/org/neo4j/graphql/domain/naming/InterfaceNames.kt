package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.domain.directives.InterfaceAnnotations

class InterfaceNames(
    name: String,
    annotations: InterfaceAnnotations
) : ImplementingTypeNames(name, annotations) {

    val implementationEnumTypename get() = "${name}Implementation"
}
