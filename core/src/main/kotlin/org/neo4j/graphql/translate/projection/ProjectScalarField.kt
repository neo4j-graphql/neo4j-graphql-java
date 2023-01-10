package org.neo4j.graphql.translate.projection

import org.neo4j.cypherdsl.core.PropertyContainer
import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.fields.PointField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.domain.fields.TemporalField
import org.neo4j.graphql.name
import org.neo4j.graphql.utils.ResolveTree

fun projectScalarField(
    selection: ResolveTree,
    field: ScalarField,
    propertyContainer: PropertyContainer,
    shortcut: Boolean = false
): List<Any> {
    val result = mutableListOf<Any>()
    result += selection.aliasOrName

    if (field is PointField) {
        TODO("implement PointField")
    }

    if (field is TemporalField && field.typeMeta.type.name() == Constants.DATE_TIME) {
        TODO("implement TemporalField DATE_TIME")
    }

    if (!shortcut || selection.aliasOrName != field.dbPropertyName) {
        result += propertyContainer.property(field.dbPropertyName)
    }
    return result
}
