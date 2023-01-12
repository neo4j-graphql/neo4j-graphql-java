package org.neo4j.graphql.schema.model.inputs.field_arguments

import graphql.language.InputValueDefinition
import graphql.language.ListType
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionSort
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.wrapList

class ConnectionFieldInputArgs(field: ConnectionField, data: Map<String, *>) {

    val where = data[Constants.WHERE]?.let { ConnectionWhere.create(field.relationshipField, Dict(it)) }
    val first = data[Constants.FIRST] as? Int
    val after = data[Constants.AFTER] as? String
    val directed = data[Constants.DIRECTED] as? Boolean
    val sort = data[Constants.SORT]
        ?.wrapList()
        ?.map { ConnectionSort(Dict(it)) }
        ?: emptyList()

    object Augmentation : AugmentationBase {

        fun getFieldArguments(field: ConnectionField, ctx: AugmentationContext): List<InputValueDefinition> {
            val args = mutableListOf<InputValueDefinition>()

            ConnectionWhere.Augmentation
                .generateConnectionWhereIT(field, ctx)
                ?.let { args += inputValue(Constants.WHERE, it.asType()) }

            args += inputValue(Constants.FIRST, Constants.Types.Int)
            args += inputValue(Constants.AFTER, Constants.Types.String)

            RelationFieldInputArgs.Augmentation.directedArgument(field.relationshipField, ctx)?.let { args += it }

            ConnectionSort.Augmentation
                .generateConnectionSortIT(field, ctx)
                ?.let { args += inputValue(Constants.SORT, ListType(it.asRequiredType())) }

            return args
        }
    }
}
