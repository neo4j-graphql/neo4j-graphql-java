package org.neo4j.graphql.schema.model.inputs.filter

import org.neo4j.graphql.Constants
import org.neo4j.graphql.schema.model.inputs.Dict

class FulltextInput(data: Dict) {
    val phrase = data.nestedObject(Constants.FULLTEXT_PHRASE) as String
    val score = data.nestedObject(Constants.FULLTEXT_SCORE_EQUAL) as? Number
}
