package org.neo4j.graphql.domain.directives

import graphql.language.*
import graphql.parser.Parser
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.getTypeByName
import org.neo4j.graphql.name
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.utils.ResolveTreeBuilder
import org.neo4j.graphql.utils.SelectionOfType
import kotlin.Annotation

class CustomResolverDirective private constructor(
    val requires: SelectionOfType?,
) : Annotation {

    companion object {

        private const val NAME = "customResolver"

        private const val INVALID_REQUIRED_FIELD_ERROR =
            "It is not possible to require fields that use the following directives: @customResolver"
        private const val INVALID_SELECTION_SET_ERROR = "Invalid selection set passed to @customResolver requires"


        internal fun create(
            directive: Directive,
            ownerName: String?,
            typeDefinitionRegistry: TypeDefinitionRegistry,
        ): CustomResolverDirective {
            val requires = directive.readArgument(CustomResolverDirective::requires) {
                val requiresString = (it as StringValue).value

                requireNotNull(ownerName) { "Parent type is required to resolve nested selection set" }

                val selectionSet = (Parser().parseDocument("{$requiresString}")
                    ?.definitions?.singleOrNull() as? OperationDefinition)
                    ?.selectionSet
                    ?: error(INVALID_SELECTION_SET_ERROR)

                val builder = ResolveTreeBuilder()
                buildResolveTree(typeDefinitionRegistry, selectionSet, ownerName, builder)
                return@readArgument builder.getFieldsByTypeName()[ownerName]

            }
            return CustomResolverDirective(requires)
        }

        private fun buildResolveTree(
            typeDefinitionRegistry: TypeDefinitionRegistry,
            selectionSet: SelectionSet,
            ownerName: String,
            builder: ResolveTreeBuilder
        ) {
            val fields by lazy { getObjectFields(ownerName, typeDefinitionRegistry) }
            selectionSet.selections.forEach { selection ->
                when (selection) {

                    is FragmentSpread -> error("Fragment spreads are not supported in @customResolver requires")

                    is InlineFragment -> {
                        if (selection.selectionSet == null) return@forEach
                        val fieldType = selection.typeCondition?.name ?: error(INVALID_SELECTION_SET_ERROR)
                        buildResolveTree(
                            typeDefinitionRegistry,
                            selection.selectionSet,
                            fieldType,
                            builder
                        )

                    }

                    is Field -> {
                        builder.select(selection.name, ownerName) {

                            if (selection.selectionSet != null) {
                                val field = fields.find { it.name == selection.name }
                                val fieldType = field?.type?.name() ?: error(INVALID_SELECTION_SET_ERROR)

                                buildResolveTree(
                                    typeDefinitionRegistry,
                                    selection.selectionSet,
                                    fieldType,
                                    this
                                )
                            }

                            validateRequiredField(selection, ownerName, fields, typeDefinitionRegistry)
                        }
                    }
                }
            }
        }


        private fun getObjectFields(
            fieldType: String,
            typeDefinitionRegistry: TypeDefinitionRegistry
        ): List<FieldDefinition> {
            return (typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(fieldType)
                ?: typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(fieldType))
                ?.fieldDefinitions
                ?: typeDefinitionRegistry.getTypeByName<UnionTypeDefinition>(fieldType)?.memberTypes?.flatMap {
                    (typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(it.name())
                        ?: typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it.name()))
                        ?.fieldDefinitions
                        ?: emptyList()
                }
                ?: error(INVALID_SELECTION_SET_ERROR)
        }

        private fun validateRequiredField(
            selection: Field,
            ownerType: String?,
            fields: List<FieldDefinition>,
            typeDefinitionRegistry: TypeDefinitionRegistry,
        ) {
            val fieldImplementations = mutableListOf(fields.find { it.name == selection.name })

            typeDefinitionRegistry
                .getTypes(ObjectTypeDefinition::class.java)
                .filter { obj -> obj.implements.any { it.name() == ownerType } }
                .flatMap { it.fieldDefinitions }
                .filterTo(fieldImplementations) { it.name == selection.name }

            if (fieldImplementations.filterNotNull().any { !it.getDirectives(NAME).isNullOrEmpty() }) {
                throw IllegalArgumentException(INVALID_REQUIRED_FIELD_ERROR)
            }
        }
    }
}

