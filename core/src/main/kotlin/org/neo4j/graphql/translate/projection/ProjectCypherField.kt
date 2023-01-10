package org.neo4j.graphql.translate.projection

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.PropertyContainer
import org.neo4j.graphql.domain.fields.CypherField
import org.neo4j.graphql.isList
import org.neo4j.graphql.utils.ResolveTree

fun projectCypherField(
    selection: ResolveTree,
    field: CypherField,
    propertyContainer: PropertyContainer,
): Expression {

    val referenceNode = field.node
    val isArray = field.typeMeta.type.isList()
    val expectMultipleValues = referenceNode != null && isArray;

    TODO()
}
