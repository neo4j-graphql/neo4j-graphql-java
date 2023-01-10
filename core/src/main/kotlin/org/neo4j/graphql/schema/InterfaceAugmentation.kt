package org.neo4j.graphql.schema

import graphql.language.InputValueDefinition
import graphql.language.ListType
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation
import kotlin.reflect.KFunction1

class InterfaceAugmentation(val interfaze: Interface, ctx: AugmentationContext) : BaseAugmentationV2(ctx) {

    fun addInterfaceField(
        suffix: String,
        implementationResolver: (Node) -> String?,
        relationFieldsResolver: KFunction1<RelationFieldBaseAugmentation, String?>,
        asList: Boolean = true,
        getAdditionalFields: (() -> List<InputValueDefinition>)? = null,
    ) = getOrCreateInputObjectType("${interfaze.name}$suffix") { fields, _ ->
        addOnField("Implementations$suffix", fields, asList, implementationResolver)
        interfaze.relationFields.forEach { r ->
            getTypeFromRelationField(r, relationFieldsResolver)
                ?.let { fields += inputValue(r.fieldName, it.wrapType(r)) }
        }
        getAdditionalFields?.invoke()?.let { fields += it }
    }

    fun addOnField(
        inputTypeSuffix: String,
        fields: MutableList<InputValueDefinition>,
        asList: Boolean,
        getNodeType: (Node) -> String?
    ) {
        generateImplementationDelegate(inputTypeSuffix, asList = asList, getNodeType)?.let {
            fields += inputValue(Constants.ON, it.asType())
        }
    }

    fun generateImplementationDelegate(
        inputTypeSuffix: String,
        asList: Boolean,
        getNodeType: (Node) -> String?,
        getAdditionalFields: (() -> List<InputValueDefinition>)? = null,
    ) =
        getOrCreateInputObjectType("${interfaze.name}$inputTypeSuffix") { fields, _ ->
            interfaze.implementations.values.forEach { node ->
                getNodeType(node)?.let {
                    val type = when (asList) {
                        true -> ListType(it.asRequiredType())
                        else -> it.asType()
                    }
                    fields += inputValue(node.name, type)
                }
            }
            getAdditionalFields?.invoke()?.let { fields += it }
        }
}
