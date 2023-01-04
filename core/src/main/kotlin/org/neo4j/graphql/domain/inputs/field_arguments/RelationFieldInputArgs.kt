package org.neo4j.graphql.domain.inputs.field_arguments

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.options.OptionsInput

class RelationFieldInputArgs(field: RelationField, data: Map<String, *>) {

    val where = data[Constants.WHERE]?.let { WhereInput.create(field, Dict(it)) }

    val options = OptionsInput
        .create(data[Constants.OPTIONS])
        .merge(field.node?.queryOptions)
}
