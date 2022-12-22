package org.neo4j.graphql.domain.inputs.filter

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.options.OptionsInput

class ReadResolverInputs(node: Node, args: Map<String, *>) {

    val fulltext = args[Constants.FULLTEXT]?.let { FulltextPerIndex(Dict(it)) }

    val options = OptionsInput.create(args[Constants.OPTIONS])

    val where = args[Constants.WHERE]?.let { WhereInput.NodeWhereInput(node, Dict(it)) }

}
