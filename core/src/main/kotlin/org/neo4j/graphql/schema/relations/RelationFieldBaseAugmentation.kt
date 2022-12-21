package org.neo4j.graphql.schema.relations

import graphql.language.InputValueDefinition
import graphql.language.ListType
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.BaseAugmentationV2

/**
 * Base class to augment a relation field
 */
abstract class RelationFieldBaseAugmentation(
    ctx: AugmentationContext,
    rel: RelationField,
    private val isArray: Boolean = rel.typeMeta.type.isList(),
    val properties: RelationshipProperties? = rel.properties,
) : BaseAugmentationV2(ctx) {

    abstract fun generateFieldCreateIT(): String?

    abstract fun generateFieldUpdateIT(): String?

    abstract fun generateFieldConnectOrCreateIT(): String?

    abstract fun generateFieldConnectIT(): String?

    abstract fun generateFieldDisconnectIT(): String?

    abstract fun generateFieldDeleteIT(): String?

    abstract fun generateFieldRelationCreateIT(): String?

    abstract fun generateFieldWhereIT(): String?

    abstract fun generateFieldConnectionWhereIT(): String?

    protected fun String.wrapType() = when {
        isArray -> ListType(this.asRequiredType())
        else -> this.asType()
    }

    protected fun generateFieldCreateFieldInputIT(prefix: String, node: Node) =
        getOrCreateInputObjectType(prefix + "CreateFieldInput") { fields, _ ->
            generateContainerCreateInputIT(node)?.let {
                fields += inputValue(Constants.NODE_FIELD, it.asRequiredType())
            }
            addEdgePropertyCreateInputField(fields) { it.hasRequiredNonGeneratedFields }
        }

    protected fun generateFieldConnectFieldInputIT(prefix: String, node: Node) =
        getOrCreateInputObjectType(prefix + "ConnectFieldInput") { fields, _ ->
            generateConnectWhereIT(node)?.let { fields += inputValue(Constants.WHERE, it.asType()) }
            generateContainerConnectInputIT(node)?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType()) }
            addEdgePropertyCreateInputField(fields) { it.hasRequiredNonGeneratedFields }
        }

    protected fun generateFieldDisconnectFieldInputIT(prefix: String, node: Node) =
        getOrCreateInputObjectType(prefix + "DisconnectFieldInput") { fields, _ ->
            generateFieldConnectionWhereIT(prefix, node)?.let {
                fields += inputValue(Constants.WHERE, it.asType())
            }
            generateContainerDisconnectInputIT(node)?.let {
                fields += inputValue(Constants.DISCONNECT_FIELD, it.asType())
            }
        }

    protected fun generateFieldUpdateFieldInputIT(prefix: String, node: Node) =
        getOrCreateInputObjectType(prefix + "UpdateFieldInput") { fields, _ ->
            generateFieldConnectionWhereIT(prefix, node)
                ?.let { fields += inputValue(Constants.WHERE, it.asType()) }
            generateFieldUpdateConnectionInputIT(prefix, node)
                ?.let { fields += inputValue(Constants.UPDATE_FIELD, it.asType()) }
            generateFieldConnectFieldInputIT(prefix, node)
                ?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType()) }
            generateFieldDisconnectFieldInputIT(prefix, node)
                ?.let { fields += inputValue(Constants.DISCONNECT_FIELD, it.wrapType()) }
            generateFieldCreateFieldInputIT(prefix, node)
                ?.let { fields += inputValue(Constants.CREATE_FIELD, it.wrapType()) }
            generateFieldDeleteFieldInputIT(prefix, node)
                ?.let { fields += inputValue(Constants.DELETE_FIELD, it.wrapType()) }
            generateFieldConnectOrCreateIT(prefix, node)
                ?.let { fields += inputValue(Constants.CONNECT_OR_CREATE_FIELD, it.wrapType()) }
        }

    fun generateFieldConnectionWhereIT(prefix: String, node: Node) =
        getOrCreateInputObjectType("${prefix}ConnectionWhere") { fields, name ->
            generateWhereIT(node)?.let {
                fields += inputValue(Constants.NODE_FIELD, it.asType())
                fields += inputValue(Constants.NODE_FIELD + "_NOT", it.asType())
            }
            properties?.let { generateRelationPropertiesWhereIT(it) }?.let {
                fields += inputValue(Constants.EDGE_FIELD, it.asType())
                fields += inputValue(Constants.EDGE_FIELD + "_NOT", it.asType())
            }
            if (fields.isNotEmpty()) {
                val listWhereType = ListType(name.asRequiredType())
                fields += inputValue(Constants.AND, listWhereType)
                fields += inputValue(Constants.OR, listWhereType)
            }
        }

    protected fun generateFieldDeleteFieldInputIT(prefix: String, node: Node) =
        getOrCreateInputObjectType(prefix + "DeleteFieldInput") { fields, _ ->
            generateFieldConnectionWhereIT(prefix, node)?.let {
                fields += inputValue(Constants.WHERE, it.asType())
            }
            generateContainerDeleteInputIT(node)?.let {
                fields += inputValue(Constants.DELETE_FIELD, it.asType())
            }
        }


    protected fun generateFieldNodeFieldInputIT(prefix: String, node: Node) =
        getOrCreateInputObjectType("${prefix}FieldInput") { fields, _ ->
            generateFieldCreateFieldInputIT(prefix, node)?.let {
                fields += inputValue(Constants.CREATE_FIELD, it.wrapType())
            }
            generateFieldConnectFieldInputIT(prefix, node)?.let {
                fields += inputValue(Constants.CONNECT_FIELD, it.wrapType())
            }
            generateFieldConnectOrCreateIT(prefix, node)?.let {
                fields += inputValue(Constants.CONNECT_OR_CREATE_FIELD, it.wrapType())
            }
        }

    fun generateFieldConnectOrCreateIT(parentPrefix: String, node: Node): String? {
        if (node.uniqueFields.isEmpty()) {
            return null
        }
        return getOrCreateInputObjectType("${parentPrefix}ConnectOrCreateFieldInput") { fields, name ->
            generateConnectOrCreateWhereIT(node)?.let { fields += inputValue(Constants.WHERE, it.asRequiredType()) }
            generateNodeOnCreateIT("${name}OnCreate", node)?.let {
                fields += inputValue(Constants.ON_CREATE_FIELD, it.asRequiredType())
            }
        }
    }

    private fun generateNodeOnCreateIT(name: String, node: Node) =
        getOrCreateInputObjectType(name) { fields, _ ->
            generateNodeOnCreateInputIT(node)?.let {
                fields += inputValue(Constants.NODE_FIELD, it.asRequiredType())
            }
            addEdgePropertyCreateInputField(fields) { it.hasRequiredNonGeneratedFields }
        }

    private fun generateNodeOnCreateInputIT(node: Node) =
        getOrCreateInputObjectType(node.name + "OnCreateInput") { fields, _ ->
            addScalarFields(fields, node.name, node.scalarFields, false)
        }

    private fun generateFieldUpdateConnectionInputIT(prefix: String, node: Node) =
        getOrCreateInputObjectType(prefix + "UpdateConnectionInput") { fields, _ ->
            generateContainerUpdateIT(node)?.let {
                fields += inputValue(Constants.NODE_FIELD, it.asType())
            }
            addEdgePropertyUpdateInputField(fields)
        }

    protected fun addEdgePropertyCreateInputField(
        fields: MutableList<InputValueDefinition>,
        required: (RelationshipProperties) -> Boolean = { false }
    ) =
        properties?.let { props ->
            generateContainerCreateInputIT(props.interfaceName, emptyList(), props.fields)?.let {
                fields += inputValue(Constants.EDGE_FIELD, it.asType(required(props)))
            }
        }

    protected fun addEdgePropertyUpdateInputField(fields: MutableList<InputValueDefinition>) =
        properties?.let { props ->
            generateContainerUpdateIT(props.interfaceName, emptyList(), props.fields)?.let {
                fields += inputValue(Constants.EDGE_FIELD, it.asType())
            }
        }
}
