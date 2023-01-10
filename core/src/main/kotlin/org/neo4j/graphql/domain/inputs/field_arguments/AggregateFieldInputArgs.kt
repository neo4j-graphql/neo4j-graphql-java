package org.neo4j.graphql.domain.inputs.field_arguments

import graphql.language.InputValueDefinition
import graphql.language.ListType
import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.connection.ConnectionSort
import org.neo4j.graphql.domain.inputs.connection.ConnectionWhere

class AggregateFieldInputArgs(node: Node, data: Map<String, *>) {
    val where = data[Constants.WHERE]?.let { WhereInput.NodeWhereInput(node, Dict(it)) }
    val directed = data[Constants.DIRECTED] as Boolean?

    object Augmentation {

        fun getFieldArguments(field: RelationField, ctx: AugmentationContext) : List<InputValueDefinition> {
            val args = mutableListOf<InputValueDefinition>()

            WhereInput.Augmentation
                .generateWhereOfFieldIT(field, ctx)
                ?.let { args += ctx.inputValue(Constants.WHERE, it.asType()) }

            RelationFieldInputArgs.Augmentation.directedArgument(field, ctx)?.let { args += it }

            return args
        }
    }
}
