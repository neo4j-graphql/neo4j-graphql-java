package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.domain.directives.UnionAnnotations

class UnionNames(
    name: String,
    annotations: UnionAnnotations
) : EntityNames(name, annotations)
