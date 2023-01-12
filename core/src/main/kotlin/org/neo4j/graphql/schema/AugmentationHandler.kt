package org.neo4j.graphql.schema

import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.FieldCoordinates
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node

/**
 * TODO adjust doc
 * A base class for augmenting a Node. There are 2 steps in augmenting the types:
 * 1. augmenting the [Node] by creating the relevant query / mutation fields and adding filtering and sorting
 * 2. generating a data fetcher based on a field definition. The field may be an augmented field (from step 1)
 * but can also be a user specified query / mutation field
 */
abstract class AugmentationHandler(val ctx: AugmentationContext) : AugmentationBase {

    private val typeDefinitionRegistry = ctx.typeDefinitionRegistry

    abstract fun augmentNode(node: Node): List<AugmentedField>

    data class AugmentedField(
        val coordinates: FieldCoordinates,
        val dataFetcher: DataFetcher<OldCypher>,
    )

    protected fun addResponseType(node: Node, name: String, info: TypeName) =
        ctx.getOrCreateObjectType(name) { args, _ ->
            args += field(Constants.INFO_FIELD, NonNullType(info))
            args += field(node.plural, NonNullType(ListType(node.name.asRequiredType())))
        }
            ?: throw IllegalStateException("Expected at least the info field")


    fun addQueryField(
        name: String,
        type: Type<*>,
        args: ((MutableList<InputValueDefinition>) -> Unit)?
    ): FieldCoordinates {
        val argList = mutableListOf<InputValueDefinition>()
        args?.invoke(argList)
        return addQueryField(field(name, type, argList))
    }

    fun addQueryField(fieldDefinition: FieldDefinition): FieldCoordinates {
        val queryTypeName = typeDefinitionRegistry.queryTypeName()
        addOperation(queryTypeName, fieldDefinition)
        return FieldCoordinates.coordinates(queryTypeName, fieldDefinition.name)
    }

    fun addMutationField(
        name: String,
        type: Type<*>,
        args: ((MutableList<InputValueDefinition>) -> Unit)?
    ): FieldCoordinates {
        val argList = mutableListOf<InputValueDefinition>()
        args?.invoke(argList)
        return addMutationField(field(name, type, argList))
    }

    fun addMutationField(fieldDefinition: FieldDefinition): FieldCoordinates {
        val mutationTypeName = typeDefinitionRegistry.mutationTypeName()
        addOperation(mutationTypeName, fieldDefinition)
        return FieldCoordinates.coordinates(mutationTypeName, fieldDefinition.name)
    }


    /**
     * add the given operation to the corresponding rootType
     */
    private fun addOperation(rootTypeName: String, fieldDefinition: FieldDefinition) {
        val rootType = typeDefinitionRegistry.getType(rootTypeName)?.unwrap()
        if (rootType == null) {
            typeDefinitionRegistry.add(
                ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(rootTypeName)
                    .fieldDefinition(fieldDefinition)
                    .build()
            )
        } else {
            val existingRootType = (rootType as? ObjectTypeDefinition
                ?: throw IllegalStateException("root type $rootTypeName is not an object type but ${rootType.javaClass}"))
            if (existingRootType.fieldDefinitions.find { it.name == fieldDefinition.name } != null) {
                return // definition already exists, we don't override it
            }
            typeDefinitionRegistry.remove(rootType)
            typeDefinitionRegistry.add(rootType.transform { it.fieldDefinition(fieldDefinition) })
        }
    }
}

