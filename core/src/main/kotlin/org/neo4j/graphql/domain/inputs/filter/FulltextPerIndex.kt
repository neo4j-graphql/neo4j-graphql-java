package org.neo4j.graphql.domain.inputs.filter

import org.neo4j.graphql.domain.inputs.Dict

class FulltextPerIndex(data: Map<String, FulltextInput>) : Map<String, FulltextInput> by data {
    constructor(data: Dict) : this(data.mapValues { entry -> FulltextInput(Dict(entry.value)) })
}
