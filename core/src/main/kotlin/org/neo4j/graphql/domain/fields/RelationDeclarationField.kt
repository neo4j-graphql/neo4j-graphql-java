package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.directives.RelationshipBaseDirective
import org.neo4j.graphql.domain.naming.RelationshipDeclarationNames

/**
 * Representation of the `@declareRelationship` directive and its meta.
 */
class RelationDeclarationField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
    val passThrough: RelationDeclarationField?,
) : RelationBaseField(
    fieldName,
    typeMeta,
    annotations
) {

    val root: RelationDeclarationField get() = passThrough?.root ?: this

    override val namings = RelationshipDeclarationNames(this, annotations)

    override val relationshipBaseDirective
        get():RelationshipBaseDirective = requireNotNull(
            annotations.relationshipBaseDirective ?: passThrough?.relationshipBaseDirective
        )

    val relationshipImplementations: List<RelationField> by lazy {
        (owner as Interface).implementations.values
            .mapNotNull { it.getField(fieldName) }
            .filterIsInstance<RelationField>()
    }
}
