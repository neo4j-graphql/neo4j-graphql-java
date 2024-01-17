package org.neo4j.graphql.domain.naming

import org.atteo.evo.inflector.EnglischInflector
import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.Entity
import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.utils.CamelCaseUtils

sealed class BaseNames(
    val name: String,
    val annotations: Annotations
) {
    protected val singular = leadingUnderscores(name) + CamelCaseUtils.camelCase(name)
    val plural = annotations.plural?.value
        ?.let { Entity.leadingUnderscores(it) + CamelCaseUtils.camelCase(it) }
        ?: EnglischInflector.getPlural(singular)
    val pluralKeepCase = EnglischInflector.getPlural(name)

    protected val pascalCaseSingular = singular.capitalize()

    val pascalCasePlural = plural.capitalize()

    open val whereInputTypeName get() = "${name}Where"

    val subscriptionEventPayloadTypeName get() = "${name}EventPayload"
    val subscriptionRelationsEventPayloadTypeName get() = "${name}ConnectedRelationships"

    open val rootTypeFieldNames get() = RootTypeFieldNames()

    open inner class RootTypeFieldNames {
        val read get() = plural
    }

    companion object {
        protected fun leadingUnderscores(name: String): String {
            return Regex("^(_+).+").matchEntire(name)?.groupValues?.get(1) ?: "";
        }
    }
}
