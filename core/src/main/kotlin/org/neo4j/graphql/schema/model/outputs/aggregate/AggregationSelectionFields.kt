package org.neo4j.graphql.schema.model.outputs.aggregate

import graphql.language.FieldDefinition
import graphql.language.NonNullType
import graphql.language.Type
import graphql.language.TypeName
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.PrimitiveField
import org.neo4j.graphql.getUnwrappedType
import org.neo4j.graphql.isList
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.utils.ResolveTree

class AggregationSelectionFields(data: Map<PrimitiveField, List<ResolveTree>>) :
    Map<PrimitiveField, List<ResolveTree>> by data {

    companion object {
        fun create(
            fieldContainer: FieldContainer<*>,
            selectedFields: Map<String, ResolveTree>,
            includeFieldsPredicates: (name: String) -> Boolean = { true }
        ): AggregationSelectionFields {
            val data = selectedFields
                .values
                .mapNotNull {
                    if (!includeFieldsPredicates(it.name)) {
                        return@mapNotNull null
                    }
                    val field = fieldContainer.getField(it.name) as? PrimitiveField ?: return@mapNotNull null
                    return@mapNotNull field to it
                }
                .groupBy({ it.first }, { it.second })

            return AggregationSelectionFields(data)
        }
    }

    object Augmentation : AugmentationBase {

        fun createAggregationField(name: String, relFields: List<BaseField>, ctx: AugmentationContext) =
            ctx.getOrCreateObjectType(name) { fields, _ ->
                fields += getAggregationFields(relFields, ctx)
            }

        fun getAggregationFields(
            fields: List<BaseField>,
            ctx: AugmentationContext,
        ): List<FieldDefinition> {
            return fields
                .filterIsInstance<PrimitiveField>()
                .filter { it.annotations.selectable?.onAggregate != false }
                .filterNot { it.typeMeta.type.isList() }
                .mapNotNull { field ->
                    getAggregationSelectionLibraryType(field, ctx)
                        ?.let { field(field.fieldName, NonNullType(it)) }
                }
        }

        private fun getAggregationSelectionLibraryType(field: PrimitiveField, ctx: AugmentationContext): Type<*>? {
            val name = field.getAggregationSelectionLibraryTypeName()
            ctx.neo4jTypeDefinitionRegistry.getUnwrappedType(name) ?: return null
            return TypeName(name)
        }
    }
}
