package org.neo4j.graphql.schema

import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.TypeName
import graphql.schema.DataFetcher
import graphql.schema.FieldCoordinates
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.PrimitiveField

/**
 * TODO adjust doc
 * A base class for augmenting a Node. There are 2 steps in augmenting the types:
 * 1. augmenting the [Node] by creating the relevant query / mutation fields and adding filtering and sorting
 * 2. generating a data fetcher based on a field definition. The field may be an augmented field (from step 1)
 * but can also be a user specified query / mutation field
 */
abstract class AugmentationHandlerV2(ctx: AugmentationContext) : BaseAugmentationV2(ctx) {

    abstract fun augmentNode(node: Node): AugmentedField?

    data class AugmentedField(
        val coordinates: FieldCoordinates,
        val dataFetcher: DataFetcher<OldCypher>,
    )

    protected fun addAggregationSelectionType(node: Node): String {
        return getOrCreateObjectType("${node.name}AggregateSelection") { fields, _ ->
            fields += field(Constants.COUNT, NonNullType(Constants.Types.Int))
            fields += node.fields
                .filterIsInstance<PrimitiveField>()
                .filterNot { it.typeMeta.type.isList() }
                .mapNotNull { field ->
                    getAggregationSelectionLibraryType(field.typeMeta.type)?.let { field(field.fieldName, NonNullType(it)) }
                }
        } ?: throw IllegalStateException("Expected at least the count field")
    }



    protected fun addResponseType(operation: String, node: Node) =
        getOrCreateObjectType("${operation}${node.pascalCasePlural}MutationResponse") { args, _ ->
            args += field(Constants.INFO_FIELD, NonNullType(TypeName(operation + "Info")))
            args += field(node.plural, NonNullType(ListType(node.name.asRequiredType())))
        }
            ?: throw IllegalStateException("Expected at least the info field")
}

