package org.neo4j.graphql.schema.model.inputs

import graphql.language.InputValueDefinition
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.fields.HasDefaultValue
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext

class ScalarProperties private constructor(data: Map<ScalarField, List<UpdateInput>>) :
    Map<ScalarField, List<ScalarProperties.UpdateInput>> by data {

    class UpdateInput(
        val value: Any?,
        val operation: ScalarField.ScalarUpdateOperation? = null
    )

    companion object {

        fun create(data: Map<String, *>, fieldContainer: FieldContainer<in ScalarField>?): ScalarProperties? {
            if (fieldContainer == null) return null
            return data.mapNotNull { (key, value) ->
                val field = fieldContainer.getField(key) as? ScalarField
                if (field != null) {
                    return@mapNotNull field to UpdateInput(value)
                }
                fieldContainer.updateDefinitions[key]?.let { op ->
                    return@mapNotNull op.first to UpdateInput(value, op.second)
                }
                null
            }
                .groupBy({ it.first }, { it.second })
                .takeIf { it.isNotEmpty() }
                ?.let { ScalarProperties(it) }
        }

        object Augmentation : AugmentationBase {

            fun addScalarFields(
                fields: MutableList<InputValueDefinition>,
                scalarFields: List<ScalarField>,
                update: Boolean,
                ctx: AugmentationContext
            ) {
                scalarFields
                    .filter { if (update) it.isUpdateInputField() else it.isCreateInputField() }
                    .forEach { field ->
                        val type = if (update) {
                            field.typeMeta.updateType
                                ?: throw IllegalStateException("missing type on ${field.getOwnerName()}.${field.fieldName} for update")
                        } else {
                            field.typeMeta.createType
                                ?: throw IllegalStateException("missing type on ${field.getOwnerName()}.${field.fieldName} for create")
                        }
                        fields += inputValue(field.fieldName, type) {
                            if (!update && field is HasDefaultValue) {
                                defaultValue(field.defaultValue)
                            }
                            field.deprecatedDirective?.let { directive(it) }
                        }
                        if (update) {
                            field.updateDefinitions.forEach { (key, op) ->
                                fields += inputValue(key, op.type ?: type)
                            }
                        }
                    }
            }
        }
    }
}
