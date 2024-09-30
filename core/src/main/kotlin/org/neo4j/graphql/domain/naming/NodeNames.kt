package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.domain.directives.NodeAnnotations

class NodeNames(
    name: String,
    annotations: NodeAnnotations
) : ImplementingTypeNames(name, annotations)
