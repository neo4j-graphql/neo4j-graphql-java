package org.neo4j.graphql.schema.model.inputs.field_arguments

import graphql.language.BooleanValue
import graphql.language.InputValueDefinition
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.options.OptionsInput

class RelationFieldInputArgs(field: RelationField, data: Dict) {

    val where = data.nestedDict(Constants.WHERE)
        ?.let { WhereInput.create(field, it) }

    val directed = data.nestedObject(Constants.DIRECTED) as? Boolean

    val options = OptionsInput
        .create(data.nestedDict(Constants.OPTIONS))
        .merge(field.node?.annotations?.limit)

    object Augmentation : AugmentationBase {

        fun getFieldArguments(field: RelationBaseField, ctx: AugmentationContext): List<InputValueDefinition> {
            val args = mutableListOf<InputValueDefinition>()

            WhereInput.Augmentation
                .generateWhereOfFieldIT(field.declarationOrSelf, ctx)
                ?.let { args += inputValue(Constants.WHERE, it.asType()) }

            val optionType = (field.declarationOrSelf).extractOnTarget(
                onImplementingType = { OptionsInput.Augmentation.generateOptionsIT(it, ctx).asType() },
                onUnion = { Constants.Types.QueryOptions },
            )
            args += inputValue(Constants.OPTIONS, optionType)

            directedArgument(field)?.let { args += it }

            return args
        }

        fun directedArgument(relationshipField: RelationBaseField): InputValueDefinition? =
            when ((relationshipField as? RelationField)?.queryDirection) {
                RelationField.QueryDirection.DEFAULT_DIRECTED -> true
                RelationField.QueryDirection.DEFAULT_UNDIRECTED -> false
                else -> null
            }?.let { defaultVal ->
                inputValue(Constants.DIRECTED, Constants.Types.Boolean) { defaultValue(BooleanValue(defaultVal)) }
            }
    }
}
