package org.neo4j.graphql.schema.model.inputs.field_arguments

import graphql.language.InputValueDefinition
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.WhereInput

class RelationFieldAggregateInputArgs(node: Node, data: Dict) {

    val where = data.nestedDict(Constants.WHERE)
        ?.let { WhereInput.NodeWhereInput(node, it) }

    val directed = data.nestedObject(Constants.DIRECTED) as Boolean?

    object Augmentation : AugmentationBase {

        fun getFieldArguments(field: RelationField, ctx: AugmentationContext): List<InputValueDefinition> {
            val args = mutableListOf<InputValueDefinition>()

            WhereInput.Augmentation
                .generateWhereOfFieldIT(field, ctx)
                ?.let { args += inputValue(Constants.WHERE, it.asType()) }

            RelationFieldInputArgs.Augmentation.directedArgument(field)?.let { args += it }

            return args
        }
    }
}
