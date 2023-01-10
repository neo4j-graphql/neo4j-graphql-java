package org.neo4j.graphql.domain.inputs

import graphql.language.InputValueDefinition
import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.fields.HasDefaultValue
import org.neo4j.graphql.domain.fields.PointField
import org.neo4j.graphql.domain.fields.PrimitiveField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.isList
import org.neo4j.graphql.name

class ScalarProperties private constructor(data: Map<ScalarField, Any?>) : Map<ScalarField, Any?> by data {
    companion object {

        fun create(data: Map<String, *>, fieldContainer: FieldContainer<in ScalarField>?): ScalarProperties? {
            if (fieldContainer == null) return null
            return data
                .mapNotNull { (key, value) ->
                    val field = fieldContainer.getField(key) as? ScalarField ?: return@mapNotNull null
                    field to value
                }
                .let { ScalarProperties(it.toMap()) }
        }

        object Augmentation {

            fun addScalarFields(
                fields: MutableList<InputValueDefinition>,
                sourceName: String,
                scalarFields: List<ScalarField>,
                update: Boolean,
                ctx: AugmentationContext
            ) {
                scalarFields
                    .filterNot { it.generated || (update && it.readonly)}
                    .forEach { field ->
                        val type = if (update) {
                            field.typeMeta.updateType
                                ?: throw IllegalStateException("missing type on $sourceName.${field.fieldName} for update")
                        } else {
                            field.typeMeta.createType
                                ?: throw IllegalStateException("missing type on $sourceName.${field.fieldName} for create")
                        }
                        fields += ctx.inputValue(field.fieldName, type) {
                            if (!update && field is HasDefaultValue) {
                                defaultValue(field.defaultValue)
                            }
                            directives(field.deprecatedDirectives)
                        }
                        if (update) {
                            if (field.typeMeta.type.isList()
                                &&
                                (field is PrimitiveField // TODO remove after https://github.com/neo4j/graphql/issues/2677
                                        || field is PointField)
                            ) {
                                fields += ctx.inputValue(
                                    "${field.fieldName}_${Constants.ArrayOperations.POP}",
                                    Constants.Types.Int
                                )
                                fields += ctx.inputValue("${field.fieldName}_${Constants.ArrayOperations.PUSH}", type)
                            } else {
                                when (field.typeMeta.type.name()) {
                                    Constants.INT, Constants.BIG_INT -> listOf(
                                        Constants.Math.INCREMENT,
                                        Constants.Math.DECREMENT
                                    ).forEach {
                                        fields += ctx.inputValue("${field.fieldName}_$it", type)
                                    }

                                    Constants.FLOAT -> listOf(
                                        Constants.Math.ADD,
                                        Constants.Math.SUBTRACT,
                                        Constants.Math.DIVIDE,
                                        Constants.Math.MULTIPLY
                                    ).forEach {
                                        fields += ctx.inputValue("${field.fieldName}_$it", type)
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }
}
