package org.neo4j.graphql.translate

import org.neo4j.graphql.Constants
import org.neo4j.graphql.Neo4jGraphQLAuthenticationError
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthenticationDirective

fun checkAuthentication(
    node: Node,
    context: QueryContext,
    field: String? = null,
    vararg targetOperations: AuthenticationDirective.AuthenticationOperation
) {

    val schemaLevelAnnotation = node.schema.annotations.authentication
    if (schemaLevelAnnotation != null) {
        val requiresAuthentication = targetOperations.any { schemaLevelAnnotation.operations.contains(it) }
        if (requiresAuthentication) {
            applyAuthentication(context, schemaLevelAnnotation)
        }
    }

    val annotation = if (field != null) {
        node.getField(field)?.annotations?.authentication
    } else {
        node.annotations.authentication
    }
    if (annotation != null) {
        val requiresAuthentication = targetOperations.any { annotation.operations.contains(it) }
        if (requiresAuthentication) {
            applyAuthentication(context, annotation)
        }
    }
}


fun applyAuthentication(
    context: QueryContext,
    annotation: AuthenticationDirective,
) {
    if (context.auth?.isAuthenticated != true) {
        throw Neo4jGraphQLAuthenticationError(Constants.AUTHORIZATION_UNAUTHENTICATED);
    }
    if (annotation.jwt == null) {
        return
    }

    if (context.auth.jwt == null || !annotation.jwt.filterByValues(
            context.auth.jwt,
//        context.auth.jwtClaims // TODO
        )
    ) {
        throw Neo4jGraphQLAuthenticationError(Constants.AUTHORIZATION_UNAUTHENTICATED);
    }
}
