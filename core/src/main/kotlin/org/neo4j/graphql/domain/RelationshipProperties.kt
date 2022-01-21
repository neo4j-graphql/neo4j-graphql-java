package org.neo4j.graphql.domain

import org.neo4j.graphql.domain.fields.ScalarField

/**
 * A container holding the fields of a rich relationship (relation with properties)
 */
class RelationshipProperties(
    /**
     * The name of the interface declaring the relationships properties
     */
    val interfaceName: String,
    /**
     * the fields of the rich relation
     */
    fields: List<ScalarField>
) : FieldContainer<ScalarField, RelationshipProperties>(fields)
