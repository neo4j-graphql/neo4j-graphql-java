package org.neo4j.graphql.schema.relations

import graphql.language.InputValueDefinition
import graphql.language.ListType
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField
import kotlin.reflect.KFunction1

/**
 * Augmentation for relations referencing an interface
 */
class InterfaceRelationFieldAugmentations(
    ctx: AugmentationContext,
    val rel: RelationField,
    private val interfaze: Interface = rel.interfaze
        ?: throw IllegalArgumentException("The type of ${rel.getOwnerName()}.${rel.fieldName} is expected to be an interface")
) : RelationFieldBaseAugmentation(ctx, rel) {

    private val prefix: String = rel.getOwnerName() + rel.fieldName.capitalize()

    override fun generateFieldCreateIT() = getOrCreateInputObjectType("${prefix}FieldInput") { fields, _ ->
        generateFieldRelationCreateIT()?.let {
            fields += inputValue(Constants.CREATE_FIELD, it.wrapType())
        }
        generateFieldConnectIT()?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType()) }
    }

    override fun generateFieldConnectIT() = getOrCreateInputObjectType("${prefix}ConnectFieldInput") { fields, _ ->
        addInterfaceField(
            "ConnectInput",
            ::generateContainerConnectInputIT,
            RelationFieldBaseAugmentation::generateFieldConnectIT
        )
            ?.let { fields += inputValue(Constants.CONNECT_FIELD, it.asType()) }
        addEdgePropertyCreateInputField(fields, required = { it.hasRequiredFields })
        generateConnectWhereIT()
            ?.let { fields += inputValue(Constants.WHERE, it.asType()) }
    }

    override fun generateFieldDeleteIT() = getOrCreateInputObjectType("${prefix}DeleteFieldInput") { fields, _ ->
        addInterfaceField(
            "DeleteInput",
            ::generateContainerDeleteInputIT,
            RelationFieldBaseAugmentation::generateFieldDeleteIT
        )
            ?.let { fields += inputValue(Constants.DELETE_FIELD, it.asType()) }
        generateFieldConnectionWhereIT()
            ?.let { fields += inputValue(Constants.WHERE, it.asType()) }
    }

    override fun generateFieldDisconnectIT() =
        getOrCreateInputObjectType("${prefix}DisconnectFieldInput") { fields, _ ->
            addInterfaceField(
                "DisconnectInput",
                ::generateContainerDisconnectInputIT,
                RelationFieldBaseAugmentation::generateFieldDisconnectIT
            )
                ?.let { fields += inputValue(Constants.DISCONNECT_FIELD, it.asType()) }
            generateFieldConnectionWhereIT()?.let {
                fields += inputValue(Constants.WHERE, it.asType())
            }
        }

    override fun generateFieldRelationCreateIT() =
        getOrCreateInputObjectType("${prefix}CreateFieldInput") { fields, _ ->
            generateCreateInputIT()?.let {
                fields += inputValue(Constants.NODE_FIELD, it.asRequiredType())
            }
            addEdgePropertyCreateInputField(fields, required = { true })
        }

    override fun generateFieldConnectOrCreateIT(): String? = null

    override fun generateFieldUpdateIT() =
        getOrCreateInputObjectType("${prefix}UpdateFieldInput") { fields, _ ->
            generateFieldConnectIT()?.let {
                fields += inputValue(Constants.CONNECT_FIELD, it.wrapType())
            }
            generateFieldRelationCreateIT()?.let {
                fields += inputValue(Constants.CREATE_FIELD, it.wrapType())
            }
            generateFieldDeleteIT()?.let {
                fields += inputValue(Constants.DELETE_FIELD, it.wrapType())
            }
            generateFieldDisconnectIT()?.let {
                fields += inputValue(Constants.DISCONNECT_FIELD, it.wrapType())
            }
            generateFieldUpdateConnectionInputIT()?.let {
                fields += inputValue(Constants.UPDATE_FIELD, it.asType())
            }
            generateFieldConnectionWhereIT()?.let {
                fields += inputValue(Constants.WHERE, it.asType())
            }
        }

    override fun generateFieldWhereIT(): String? = getOrCreateInputObjectType("${interfaze.name}Where") { fields, _ ->
        addOnField("ImplementationsWhere", fields, asList = false, ::generateWhereIT)
        fields += getWhereFields(interfaze.name, interfaze.fields, isInterface = true, plural = interfaze.pascalCasePlural)
    }

    // TODO merge with super.addConnectionWhere(prefix: String, node: Node)
    override fun generateFieldConnectionWhereIT() =
        getOrCreateInputObjectType(rel.connectionField.typeMeta.whereType.name()) { fields, connectionWhereName ->
            generateFieldWhereIT()?.let {
                fields += inputValue(Constants.NODE_FIELD, it.asType())
                fields += inputValue(Constants.NODE_FIELD + "_NOT", it.asType())
            }
            properties?.let { generateRelationPropertiesWhereIT(it) }?.let {
                fields += inputValue(Constants.EDGE_FIELD, it.asType())
                fields += inputValue(Constants.EDGE_FIELD + "_NOT", it.asType())
            }
            if (fields.isNotEmpty()) {
                val listWhereType = ListType(connectionWhereName.asRequiredType())
                fields += inputValue("AND", listWhereType)
                fields += inputValue("OR", listWhereType)
            }
        }

    private fun generateFieldUpdateConnectionInputIT() =
        getOrCreateInputObjectType("${prefix}UpdateConnectionInput") { fields, _ ->
            generateUpdateInputIT()?.let {
                fields += inputValue(
                    Constants.NODE_FIELD,
                    it.asType()
                )
            }
            addEdgePropertyUpdateInputField(fields)
        }

    private fun generateUpdateInputIT() = addInterfaceField(
        "UpdateInput",
        ::generateContainerUpdateIT,
        RelationFieldBaseAugmentation::generateFieldUpdateIT,
        asList = false
    ) {
        interfaze.scalarFields.filterNot { it.generated || it.readonly }
            .mapNotNull { field -> field.typeMeta.updateType?.let { inputValue(field.fieldName, it) } }
    }

    private fun generateCreateInputIT() =
        generateImplementationDelegate("CreateInput", asList = false, ::generateContainerCreateInputIT) {
            // TODO REVIEW Darrell
            //    interface-relationships_--nested-relationships.adoc
            //  vs
            //   interface-relationships_--nested-interface-relationships.adoc
//                interfaze.relationFields.mapNotNull { r ->
//                    getTypeFromRelationField(interfaze.name, r, RelationAugmentation::addCreateType)
//                        ?.let { inputValue(r.fieldName, it.asType()) }
//                }
            emptyList()
        }

    private fun addInterfaceField(
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

    private fun addOnField(
        inputTypeSuffix: String,
        fields: MutableList<InputValueDefinition>,
        asList: Boolean,
        getNodeType: (Node) -> String?
    ) {
        generateImplementationDelegate(inputTypeSuffix, asList = asList, getNodeType)?.let {
            fields += inputValue(Constants.ON, it.asType())
        }
    }

    private fun generateImplementationDelegate(
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


    private fun generateConnectWhereIT() =
        getOrCreateInputObjectType("${rel.typeMeta.type.name()}ConnectWhere") { fields, _ ->
            generateFieldWhereIT()?.let { whereType ->
                fields += inputValue(Constants.NODE_FIELD, whereType.asRequiredType())
            }
        }

}
