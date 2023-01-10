package org.neo4j.graphql.schema.relations

import graphql.language.ListType
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.connect.ConnectFieldInput.NodeConnectFieldInput
import org.neo4j.graphql.domain.inputs.connect_or_create.ConnectOrCreateFieldInput
import org.neo4j.graphql.domain.inputs.connection.ConnectionWhere
import org.neo4j.graphql.domain.inputs.create.CreateFieldInput
import org.neo4j.graphql.domain.inputs.create.RelationFieldInput
import org.neo4j.graphql.domain.inputs.delete.DeleteFieldInput
import org.neo4j.graphql.domain.inputs.disconnect.DisconnectFieldInput
import org.neo4j.graphql.domain.inputs.update.UpdateFieldInput

/**
 * Augmentation for relations referencing a union
 */
class UnionRelationFieldAugmentations(
    private val ctx: AugmentationContext,
    private val rel: RelationField,
    union: Union,
) : RelationFieldBaseAugmentation {

    private val prefix: String = rel.getOwnerName() + rel.fieldName.capitalize()

    private val unionNodes: Collection<Node> = union.nodes.values

    private val isArray: Boolean = rel.typeMeta.type.isList()

    private fun Node.unionPrefix() = prefix + this.name

    override fun generateFieldCreateIT() =
        ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.CreateInput}") { fields, _ ->
            unionNodes.forEach { node ->
                CreateFieldInput.NodeFieldInput.Augmentation
                    .generateFieldNodeFieldInputIT(rel, node.unionPrefix(), node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.asType())
                    }
            }
        }

    override fun generateFieldConnectIT() =
        ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.ConnectInput}") { fields, _ ->
            unionNodes.forEach { node ->
                NodeConnectFieldInput.Augmentation
                    .generateFieldConnectFieldInputIT(rel, node.unionPrefix(), node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.wrapType())
                    }
            }
        }

    override fun generateFieldDeleteIT() =
        ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.DeleteInput}") { fields, _ ->
            unionNodes.forEach { node ->
                DeleteFieldInput.NodeDeleteFieldInput.Augmentation
                    .generateFieldDeleteFieldInputIT(rel, node.unionPrefix(), node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.wrapType())
                    }
            }
        }

    override fun generateFieldDisconnectIT() =
        ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.DisconnectInput}") { fields, _ ->
            unionNodes.forEach { node ->
                DisconnectFieldInput.NodeDisconnectFieldInput.Augmentation
                    .generateFieldDisconnectFieldInputIT(rel, node.unionPrefix(), node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.wrapType())
                    }
            }
        }

    override fun generateFieldRelationCreateIT() =
        ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.CreateFieldInput}") { fields, _ ->
            unionNodes.forEach { node ->
                RelationFieldInput.NodeCreateCreateFieldInput.Augmentation
                    .generateFieldCreateFieldInputIT(rel, node.unionPrefix(), node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.wrapType())
                    }
            }
        }

    override fun generateFieldUpdateIT() =
        ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.UpdateInput}") { fields, _ ->
            unionNodes.forEach { node ->
                UpdateFieldInput.NodeUpdateFieldInput.Augmentation
                    .generateFieldUpdateFieldInputIT(rel, node.unionPrefix(), node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.wrapType())
                    }
            }
        }

    override fun generateFieldWhereIT() =
        ctx.getOrCreateInputObjectType("${rel.typeMeta.type.name()}${Constants.InputTypeSuffix.Where}") { fields, _ ->
            unionNodes.forEach { node ->
                WhereInput.NodeWhereInput.Augmentation
                    .generateWhereIT(node, ctx)?.let { fields += ctx.inputValue(node.name, it.asType()) }
            }
        }

    override fun generateFieldConnectionWhereIT() =
        ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.ConnectionWhere}") { fields, _ ->
            unionNodes.forEach { node ->
                ConnectionWhere.NodeConnectionWhere.Augmentation
                    .generateFieldConnectionWhereIT(rel, node.unionPrefix(), node, ctx)
                    ?.let { fields += ctx.inputValue(node.name, it.asType()) }
            }
        }

    override fun generateFieldConnectOrCreateIT() =
        ctx.getOrCreateInputObjectType("${prefix}${Constants.InputTypeSuffix.ConnectOrCreateInput}") { fields, _ ->
            unionNodes.forEach { node ->
                ConnectOrCreateFieldInput.NodeConnectOrCreateFieldInput.Augmentation
                    .generateFieldConnectOrCreateIT(rel, node.unionPrefix(), node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.wrapType())
                    }
            }
        }

    protected fun String.wrapType() = when {
        isArray -> ListType(this.asRequiredType())
        else -> this.asType()
    }
}
