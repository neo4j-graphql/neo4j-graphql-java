package org.neo4j.graphql.translate.projection

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.cypherdsl.core.PropertyContainer
import org.neo4j.graphql.Constants
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.domain.fields.PointField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.domain.fields.TemporalField
import org.neo4j.graphql.isList
import org.neo4j.graphql.name
import org.neo4j.graphql.utils.ResolveTree

fun projectScalarField(
    selection: ResolveTree,
    field: ScalarField,
    propertyContainer: PropertyContainer,
    shortcut: Boolean = false,
    queryContext: QueryContext
): List<Any> {
    val result = mutableListOf<Any>()
    result += selection.aliasOrName

    val property = propertyContainer.property(field.dbPropertyName)

    if (field is PointField) {
        val pointSelection = field.parseSelection(selection)

        val projectedPoint = if (field.typeMeta.type.isList()) {
            val projectionVar = queryContext.getNextVariable("p_var")
            Cypher.listWith(projectionVar).`in`(property).returning(
                pointSelection
                    .project(projectionVar)
                    ?.let { Cypher.mapOf(*it.toTypedArray()) }
                    ?: error("expected at leas one selected field")
            )
        } else {
            pointSelection
                .project(property)
                ?.let { Cypher.mapOf(*it.toTypedArray()) }
                ?: error("expected at leas one selected field")
        }
        result += Cypher.caseExpression()
            .`when`(property.isNotNull)
            .then(projectedPoint)
            .elseDefault(Cypher.literalNull())
        return result
    }

    if (field is TemporalField && field.typeMeta.type.name() == Constants.DATE_TIME) {
        result += createDatetimeExpression(field, Functions.toString(property))
        return result
    }

    if (!shortcut || selection.aliasOrName != field.dbPropertyName) {
        result += property
    }
    return result
}
