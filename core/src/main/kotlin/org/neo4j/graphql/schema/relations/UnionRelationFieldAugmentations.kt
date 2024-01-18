package org.neo4j.graphql.schema.relations

import graphql.language.ListType
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.connect.ConnectFieldInput.NodeConnectFieldInput
import org.neo4j.graphql.schema.model.inputs.connect_or_create.ConnectOrCreateFieldInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.schema.model.inputs.create.CreateFieldInput
import org.neo4j.graphql.schema.model.inputs.create.RelationFieldInput
import org.neo4j.graphql.schema.model.inputs.delete.DeleteFieldInput
import org.neo4j.graphql.schema.model.inputs.disconnect.DisconnectFieldInput
import org.neo4j.graphql.schema.model.inputs.update.UpdateFieldInput

/**
 * Augmentation for relations referencing a union
 */
class UnionRelationFieldAugmentations(
    private val ctx: AugmentationContext,
    private val rel: RelationField,
    private val union: Union,
) : RelationFieldBaseAugmentation {

    private val unionNodes: Collection<Node> = union.nodes.values

    private val isArray: Boolean = rel.typeMeta.type.isList()

    override fun generateFieldCreateIT() =
        ctx.getOrCreateInputObjectType(rel.operations.unionCreateInputTypeName) { fields, _ ->
            unionNodes.forEach { node ->
                CreateFieldInput.NodeFieldInput.Augmentation
                    .generateFieldNodeFieldInputIT(rel, node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.asType())
                    }
            }
        }

    override fun generateFieldConnectIT() =
        ctx.getOrCreateInputObjectType(rel.operations.unionConnectInputTypeName) { fields, _ ->
            unionNodes.forEach { node ->
                NodeConnectFieldInput.Augmentation
                    .generateFieldConnectFieldInputIT(rel, node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.wrapType())
                    }
            }
        }

    override fun generateFieldDeleteIT() =
        ctx.getOrCreateInputObjectType(rel.operations.unionDeleteInputTypeName) { fields, _ ->
            unionNodes.forEach { node ->
                DeleteFieldInput.NodeDeleteFieldInput.Augmentation
                    .generateFieldDeleteFieldInputIT(rel, node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.wrapType())
                    }
            }
        }

    override fun generateFieldDisconnectIT() =
        ctx.getOrCreateInputObjectType(rel.operations.unionDisconnectInputTypeName) { fields, _ ->
            unionNodes.forEach { node ->
                DisconnectFieldInput.NodeDisconnectFieldInput.Augmentation
                    .generateFieldDisconnectFieldInputIT(rel, node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.wrapType())
                    }
            }
        }

    override fun generateFieldRelationCreateIT() =
        ctx.getOrCreateInputObjectType(rel.operations.unionCreateFieldInputTypeName) { fields, _ ->
            unionNodes.forEach { node ->
                RelationFieldInput.NodeCreateCreateFieldInput.Augmentation
                    .generateFieldCreateFieldInputIT(rel, node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.wrapType())
                    }
            }
        }

    override fun generateFieldUpdateIT() =
        ctx.getOrCreateInputObjectType(rel.operations.unionUpdateInputTypeName) { fields, _ ->
            unionNodes.forEach { node ->
                UpdateFieldInput.NodeUpdateFieldInput.Augmentation
                    .generateFieldUpdateFieldInputIT(rel, node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.wrapType())
                    }
            }
        }

    override fun generateFieldWhereIT() = WhereInput.UnionWhereInput.Augmentation.generateWhereIT(union, ctx)

    override fun generateFieldConnectionWhereIT() =
        ctx.getOrCreateInputObjectType(rel.operations.unionConnectionUnionWhereTypeName) { fields, _ ->
            unionNodes.forEach { node ->
                ConnectionWhere.NodeConnectionWhere.Augmentation
                    .generateFieldConnectionWhereIT(rel, node, ctx)
                    ?.let { fields += ctx.inputValue(node.name, it.asType()) }
            }
        }

    override fun generateFieldConnectOrCreateIT() =
        ctx.getOrCreateInputObjectType(rel.operations.connectOrCreateInputTypeName) { fields, _ ->
            unionNodes.forEach { node ->
                ConnectOrCreateFieldInput.NodeConnectOrCreateFieldInput.Augmentation
                    .generateFieldConnectOrCreateIT(rel, node, ctx)?.let {
                        fields += ctx.inputValue(node.name, it.wrapType())
                    }
            }
        }

    protected fun String.wrapType() = when {
        isArray -> ListType(this.asRequiredType())
        else -> this.asType()
    }
}
