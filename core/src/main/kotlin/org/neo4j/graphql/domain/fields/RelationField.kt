package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.TypeMeta

/**
 * Representation of the `@relationship` directive and its meta.
 */
class RelationField(
    fieldName: String,
    typeMeta: TypeMeta,
    /**
     * If the type of the field is an interface
     */
    val interfaze: Interface?,
    var union: List<String>,
    /**
     * The type of the neo4j relation
     */
    val relationType: String,
    val direction: Direction,
    val properties: RelationshipProperties?,
    /**
     * The node or interface name. If the filed is defined in an interface, the prefix will have the interface's name
     */
    val connectionPrefix: String,
) : BaseField(
    fieldName,
    typeMeta,
) {

    // TODO move to connection field?
    val relationshipTypeName: String get() = "${connectionPrefix}${fieldName.capitalize()}Relationship"

    /**
     * If the type of the field is an interface, this list contains all nodes implementing this interface
     */
    lateinit var unionNodes: List<Node>

    lateinit var connectionField: ConnectionField

    /**
     * The referenced node, if the relationship is not an interface nor a union
     */
    var node: Node? = null

    val isInterface: Boolean get() = interfaze != null
    val isUnion: Boolean get() = union.isNotEmpty()


    enum class Direction {
        IN, OUT
    }
}
