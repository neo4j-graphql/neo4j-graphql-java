package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.domain.directives.ImplementingTypeAnnotations

sealed class ImplementingTypeNames(
    name: String,
    annotations: ImplementingTypeAnnotations
) : EntityNames(name, annotations) {

    val connectOrCreateWhereInputTypeName get() = "${name}ConnectOrCreateWhere"
    val optionsInputTypeName get() = "${name}Options"
    val sortInputTypeName get() = "${name}Sort"
    override val rootTypeFieldNames get() = ImplementingTypeRootTypeFieldNames()
    val rootTypeSelection get() = ImplementingTypeRootTypeSelection()

    open inner class ImplementingTypeRootTypeFieldNames : RootTypeFieldNames() {
        val connection get() = "${plural}Connection"
    }

    open inner class ImplementingTypeRootTypeSelection {
        val connection get() = "${pascalCasePlural}Connection"
        val edge get() = "${name}Edge"
    }

}
