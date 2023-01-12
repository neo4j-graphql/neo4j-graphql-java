package org.neo4j.graphql.schema.model.inputs.filter

import org.neo4j.graphql.schema.model.inputs.Dict

class FulltextPerIndex(data: Map<String, FulltextInput>) : Map<String, FulltextInput> by data {
    constructor(data: Dict) : this(data.mapValues { entry -> FulltextInput(Dict(entry.value)) })
}
