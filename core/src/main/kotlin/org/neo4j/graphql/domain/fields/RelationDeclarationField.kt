package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.naming.RelationshipDeclarationNames

/**
 * Representation of the `@declareRelationship` directive and its meta.
 */
class RelationDeclarationField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
    connectionPrefix: String,
) : RelationBaseField(
    fieldName,
    typeMeta,
    annotations,
    connectionPrefix
) {

    override val namings = RelationshipDeclarationNames(this, annotations)

    val relationshipImplementations: List<RelationField> by lazy {
        (owner as Interface).implementations.values
            .mapNotNull { it.getField(fieldName) }
            .filterIsInstance<RelationField>()
    }
}
