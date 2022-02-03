package org.neo4j.graphql.schema.relations

import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.asType
import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.name

/**
 * Augmentation for relations referencing a union
 */
class UnionRelationFieldAugmentations(
    ctx: AugmentationContext,
    val rel: RelationField,
    private val prefix: String = rel.getOwnerName() + rel.fieldName.capitalize()
) : RelationFieldBaseAugmentation(ctx, rel) {

    init {
        if (!rel.isUnion) {
            throw IllegalArgumentException("The type of ${rel.getOwnerName()}.${rel.fieldName} is expected to be an union")
        }
    }

    private fun Node.unionPrefix() = prefix + this.name

    override fun generateFieldCreateIT() = getOrCreateInputObjectType("${prefix}CreateInput") { fields, _ ->
        rel.unionNodes.forEach { node ->
            generateFieldNodeFieldInputIT(node.unionPrefix(), node)?.let {
                fields += inputValue(node.name, it.asType())
            }
        }
    }

    override fun generateFieldConnectIT() = getOrCreateInputObjectType("${prefix}ConnectInput") { fields, _ ->
        rel.unionNodes.forEach { node ->
            generateFieldConnectFieldInputIT(node.unionPrefix(), node)?.let {
                fields += inputValue(node.name, it.wrapType())
            }
        }
    }

    override fun generateFieldDeleteIT() = getOrCreateInputObjectType("${prefix}DeleteInput") { fields, _ ->
        rel.unionNodes.forEach { node ->
            generateFieldDeleteFieldInputIT(node.unionPrefix(), node)?.let {
                fields += inputValue(node.name, it.wrapType())
            }
        }
    }

    override fun generateFieldDisconnectIT() = getOrCreateInputObjectType("${prefix}DisconnectInput") { fields, _ ->
        rel.unionNodes.forEach { node ->
            generateFieldDisconnectFieldInputIT(node.unionPrefix(), node)?.let {
                fields += inputValue(node.name, it.wrapType())
            }
        }
    }

    override fun generateFieldRelationCreateIT() =
        getOrCreateInputObjectType("${prefix}CreateFieldInput") { fields, _ ->
            rel.unionNodes.forEach { node ->
                generateFieldCreateFieldInputIT(node.unionPrefix(), node)?.let {
                    fields += inputValue(node.name, it.wrapType())
                }
            }
        }

    override fun generateFieldUpdateIT() = getOrCreateInputObjectType("${prefix}UpdateInput") { fields, _ ->
        rel.unionNodes.forEach { node ->
            generateFieldUpdateFieldInputIT(node.unionPrefix(), node)?.let {
                fields += inputValue(node.name, it.wrapType())
            }
        }
    }

    override fun generateFieldWhereIT() = getOrCreateInputObjectType("${rel.typeMeta.type.name()}Where") { fields, _ ->
        rel.unionNodes.forEach { node ->
            generateWhereIT(node)?.let { fields += inputValue(node.name, it.asType()) }
        }
    }

    override fun generateFieldConnectionWhereIT() =
        getOrCreateInputObjectType("${prefix}ConnectionWhere") { fields, _ ->
            rel.unionNodes.forEach { node ->
                generateFieldConnectionWhereIT(node.unionPrefix(), node)
                    ?.let { fields += inputValue(node.name, it.asType()) }
            }
        }

    override fun generateFieldConnectOrCreateIT() =
        getOrCreateInputObjectType("${prefix}ConnectOrCreateInput") { fields, _ ->
            rel.unionNodes.forEach { node ->
                generateFieldConnectOrCreateIT(node.unionPrefix(), node)?.let {
                    fields += inputValue(node.name, it.wrapType())
                }
            }
        }
}
