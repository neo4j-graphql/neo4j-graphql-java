package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.*

/**
 * Representation of the `@relationship` directive and its meta.
 */
class RelationField<OWNER : Any>(
    fieldName: String,
    typeMeta: TypeMeta,
    val relationship: Relationship,
    /**
     * If the type of the field is an interface
     */
    val interfaze: Interface?,
    var union: List<String>,
) : BaseField<OWNER>(
    fieldName,
    typeMeta,
) {

    var inherited: Boolean = false

    /**
     * the node or interface name
     */
    lateinit var connectionPrefix: String

    /**
     * If the type of the field is an interface, this list contains all nodes implementing this interface
     */
    lateinit var unionNodes: List<Node>

    lateinit var connectionField: ConnectionField<OWNER>

    /**
     * The referenced node, if the relationship is not an interface nor a union
     */
    var node: Node? = null

    val properties: RelationshipProperties? get() = relationship.properties
    val isInterface: Boolean get() = interfaze != null
    val isUnion: Boolean get() = union.isNotEmpty()

}
