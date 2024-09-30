package org.neo4j.graphql.domain.fields

import graphql.language.Value

interface HasDefaultValue {
    val defaultValue: Value<*>?
}
