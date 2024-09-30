package org.neo4j.graphql.schema.model.inputs.field_arguments

import graphql.language.InputValueDefinition
import org.neo4j.graphql.Constants
import org.neo4j.graphql.List
import org.neo4j.graphql.NonNull
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionSort
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.schema.model.inputs.options.OptionsInput

class ConnectionFieldInputArgs(field: ConnectionField, data: Dict) {

    val where = data.nestedDict(Constants.WHERE)
        ?.let { ConnectionWhere.create(field.relationshipField, it) }

    val options = OptionsInput.create(
        data,
        limitName = Constants.FIRST,
        offsetName = Constants.AFTER,
        sortName = Constants.SORT,
        sortFactory = { ConnectionSort(field, it) }
    )

    val directed = data.nestedObject(Constants.DIRECTED) as? Boolean

    object Augmentation : AugmentationBase {

        fun getFieldArguments(field: ConnectionField, ctx: AugmentationContext): List<InputValueDefinition> {
            val args = mutableListOf<InputValueDefinition>()

            ConnectionWhere.Augmentation
                .generateConnectionWhereIT(field, ctx)
                ?.let { args += inputValue(Constants.WHERE, it.asType()) }

            args += inputValue(Constants.FIRST, Constants.Types.Int)
            args += inputValue(Constants.AFTER, Constants.Types.String)

            RelationFieldInputArgs.Augmentation.directedArgument(field.relationshipField)?.let { args += it }

            ConnectionSort.Augmentation
                .generateConnectionSortIT(field, ctx)
                ?.let { args += inputValue(Constants.SORT, it.NonNull.List) }

            return args
        }
    }
}
