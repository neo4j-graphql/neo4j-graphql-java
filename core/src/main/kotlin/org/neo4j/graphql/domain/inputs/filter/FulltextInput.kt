package org.neo4j.graphql.domain.inputs.filter

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.inputs.Dict

class FulltextInput(data: Dict) {
    val phrase = data[Constants.FULLTEXT_PHRASE] as String
    val score = data[Constants.FULLTEXT_SCORE_EQUAL] as? Number
}
