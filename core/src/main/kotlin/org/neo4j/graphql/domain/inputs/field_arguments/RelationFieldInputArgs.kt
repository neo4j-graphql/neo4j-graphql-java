package org.neo4j.graphql.domain.inputs.field_arguments

import graphql.language.BooleanValue
import graphql.language.InputValueDefinition
import graphql.language.ListType
import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.connection.ConnectionSort
import org.neo4j.graphql.domain.inputs.connection.ConnectionWhere
import org.neo4j.graphql.domain.inputs.options.OptionsInput

class RelationFieldInputArgs(field: RelationField, data: Map<String, *>) {

    val where = data[Constants.WHERE]?.let { WhereInput.create(field, Dict(it)) }

    val directed = data[Constants.DIRECTED] as? Boolean


    val options = OptionsInput
        .create(data[Constants.OPTIONS])
        .merge(field.node?.queryOptions)

    object Augmentation {

        fun getFieldArguments(field: RelationField, ctx: AugmentationContext) : List<InputValueDefinition> {
            val args = mutableListOf<InputValueDefinition>()

            WhereInput.Augmentation
                .generateWhereOfFieldIT(field, ctx)
                ?.let { args += ctx.inputValue(Constants.WHERE, it.asType()) }

            val optionType = field.extractOnTarget(
                onImplementingType = { OptionsInput.Augmentation.generateOptionsIT(it, ctx).asType() },
                onUnion = { Constants.Types.QueryOptions },
            )
            args += ctx.inputValue(Constants.OPTIONS, optionType)

            directedArgument(field, ctx)?.let { args += it }

            return args
        }

        fun directedArgument(relationshipField: RelationField, ctx: AugmentationContext): InputValueDefinition? =
            when (relationshipField.queryDirection) {
                RelationField.QueryDirection.DEFAULT_DIRECTED -> true
                RelationField.QueryDirection.DEFAULT_UNDIRECTED -> false
                else -> null
            }?.let { defaultVal ->
                ctx.inputValue(Constants.DIRECTED, Constants.Types.Boolean) { defaultValue(BooleanValue(defaultVal)) }
            }
    }
}
