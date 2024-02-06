package org.neo4j.graphql.domain.naming

import org.atteo.evo.inflector.EnglischInflector
import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.Entity
import org.neo4j.graphql.domain.directives.EntityAnnotations
import org.neo4j.graphql.utils.CamelCaseUtils

sealed class EntityNames(
    name: String,
    annotations: EntityAnnotations
) : BaseNames<EntityAnnotations>(name, annotations) {

    val plural = annotations.plural?.value
        ?.let { Entity.leadingUnderscores(it) + CamelCaseUtils.camelCase(it) }
        ?: EnglischInflector.getPlural(singular)
    val pluralKeepCase = EnglischInflector.getPlural(name)

    protected val pascalCaseSingular = singular.capitalize()

    val pascalCasePlural = plural.capitalize()

    val subscriptionEventPayloadTypeName get() = "${name}EventPayload"
    val subscriptionRelationsEventPayloadTypeName get() = "${name}ConnectedRelationships"


    open val rootTypeFieldNames get() = RootTypeFieldNames()

    open inner class RootTypeFieldNames {
        val read get() = plural
    }

}
