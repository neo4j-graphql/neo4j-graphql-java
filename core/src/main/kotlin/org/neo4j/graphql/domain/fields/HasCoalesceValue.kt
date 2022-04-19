package org.neo4j.graphql.domain.fields

import graphql.language.Value

interface HasCoalesceValue {
    var coalesceValue: Value<*>?
}
