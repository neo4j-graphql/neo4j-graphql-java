package org.neo4j.graphql.schema.model.inputs.field_arguments

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

    val options = OptionsInput
        .create(field.implementingType, data)
        .merge(field.node)


    object Augmentation : AugmentationBase {

        fun getFieldArguments(field: RelationBaseField, ctx: AugmentationContext): List<InputValueDefinition> {
            val args = mutableListOf<InputValueDefinition>()

            WhereInput.Augmentation
                .generateWhereOfFieldIT(field, ctx)
                ?.let { args += inputValue(Constants.WHERE, it.asType()) }

            args += OptionsInput.Augmentation.generateOptionsArguments(field.target, ctx)

            return args
        }
    }
}
