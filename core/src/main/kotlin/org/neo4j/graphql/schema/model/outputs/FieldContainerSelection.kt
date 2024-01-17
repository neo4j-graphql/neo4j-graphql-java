package org.neo4j.graphql.schema.model.outputs

import graphql.language.FieldDefinition
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.field_arguments.ConnectionFieldInputArgs
import org.neo4j.graphql.schema.model.inputs.field_arguments.RelationFieldInputArgs
import org.neo4j.graphql.wrapLike

sealed class FieldContainerSelection {

    object Augmentation : AugmentationBase {

        fun mapField(field: BaseField, ctx: AugmentationContext): FieldDefinition {
            val args = field.arguments.toMutableList()
            val type = when (field) {
                is ConnectionField -> NodeConnectionFieldSelection.Augmentation
                    .generateConnectionOT(field, ctx).wrapLike(field.typeMeta.type)

                else -> field.typeMeta.type
            }

            // TODO https://github.com/neo4j/graphql/issues/869
            if (field is RelationField) {
                args += RelationFieldInputArgs.Augmentation.getFieldArguments(field, ctx)
            } else if (field is ConnectionField) {
                args += ConnectionFieldInputArgs.Augmentation.getFieldArguments(field, ctx)
            }


            return field(field.fieldName, type) {
                description(field.description)
                comments(field.comments)
                directives(field.annotations.otherDirectives)
                inputValueDefinitions(args)
            }
        }

    }
}
