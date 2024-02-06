package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthorizationDirective
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.schema.model.inputs.AuthorizationWhere
import org.neo4j.graphql.translate.where.createWhere

object AuthorizationFactory {

    fun getAuthConditions(
        node: Node,
        dslNode: org.neo4j.cypherdsl.core.Node,
        fields: Iterable<BaseField>? = null,
        schemaConfig: SchemaConfig,
        context: QueryContext,
        vararg operations: AuthorizationDirective.AuthorizationOperation,
    ): WhereResult {

        var authorizationFilters =
            createAuthFilterRule(node, node.annotations.authorization, dslNode, schemaConfig, context, *operations)
        var authorizationValidate =
            createAuthValidateRule(
                node,
                node.annotations.authorization,
                dslNode,
                schemaConfig,
                context,
                AuthorizationDirective.AuthorizationValidateStage.BEFORE,
                *operations
            )

        fields?.forEach { field ->
            authorizationFilters = authorizationFilters and createAuthFilterRule(
                node,
                field.annotations.authorization,
                dslNode,
                schemaConfig,
                context,
                *operations
            )

            authorizationValidate = authorizationValidate and createAuthValidateRule(
                node,
                field.annotations.authorization,
                dslNode,
                schemaConfig,
                context,
                AuthorizationDirective.AuthorizationValidateStage.BEFORE,
                *operations
            )
        }
        val condition = authorizationValidate.predicate
            ?.let { it.apocValidatePredicate() }
            ?.let { authorizationFilters.predicate and it } ?: authorizationFilters.predicate
        val subqeries = authorizationFilters.preComputedSubQueries + authorizationValidate.preComputedSubQueries
        return WhereResult(condition, subqeries)
    }

    fun getPostAuthConditions(
        node: Node,
        dslNode: org.neo4j.cypherdsl.core.Node,
        fields: Iterable<BaseField>? = null,
        schemaConfig: SchemaConfig,
        context: QueryContext,
        vararg operations: AuthorizationDirective.AuthorizationOperation,
    ): WhereResult {

        var result = createAuthValidateRule(
            node,
            node.annotations.authorization,
            dslNode,
            schemaConfig,
            context,
            AuthorizationDirective.AuthorizationValidateStage.AFTER,
            *operations
        )

        fields?.forEach { field ->
            result = result and createAuthValidateRule(
                node,
                field.annotations.authorization,
                dslNode,
                schemaConfig,
                context,
                AuthorizationDirective.AuthorizationValidateStage.AFTER,
                *operations
            )
        }
        return result
    }


    fun createAuthFilterRule(
        node: Node,
        authAnnotation: AuthorizationDirective?,
        dslNode: org.neo4j.cypherdsl.core.Node,
        schemaConfig: SchemaConfig,
        context: QueryContext,
        vararg operations: AuthorizationDirective.AuthorizationOperation,
    ) =
        createAuthCondition(authAnnotation?.filter, node, dslNode, schemaConfig, context, operations.toSet())

    fun createAuthValidateRule(
        node: Node,
        authAnnotation: AuthorizationDirective?,
        dslNode: org.neo4j.cypherdsl.core.Node,
        schemaConfig: SchemaConfig,
        context: QueryContext,
        `when`: AuthorizationDirective.AuthorizationValidateStage,
        vararg operations: AuthorizationDirective.AuthorizationOperation
    ) = createAuthCondition(
        authAnnotation?.validate?.filter { it.`when`.contains(`when`) },
        node,
        dslNode,
        schemaConfig,
        context,
        operations.toSet()
    )

    private fun createAuthCondition(
        rules: List<AuthorizationDirective.BaseRule>?,
        node: Node,
        dslNode: org.neo4j.cypherdsl.core.Node,
        schemaConfig: SchemaConfig,
        context: QueryContext,
        expectedOps: Set<AuthorizationDirective.AuthorizationOperation>
    ): WhereResult {
        val rulesMatchingOperations = rules
            ?.filter { it.operations.containsAny(expectedOps) }
            ?: return WhereResult.EMPTY

        var conditions: Condition? = null

        if (rulesMatchingOperations.any { it.requireAuthentication }) {
            conditions =
                conditions and Cypher.parameter("isAuthenticated", context.auth?.isAuthenticated == true).isTrue
        }
        var result = WhereResult(conditions, emptyList())
        rulesMatchingOperations.forEach { rule ->
            result = result and createAuthWhere(node, dslNode, rule.where, schemaConfig, context)
        }
        return result
    }

    private fun createAuthWhere(
        node: Node,
        dslNode: org.neo4j.cypherdsl.core.Node,
        where: AuthorizationWhere,
        schemaConfig: SchemaConfig,
        context: QueryContext
    ): WhereResult {

        var result =
            where.reduceNestedWhere { _, _, nested -> createAuthWhere(node, dslNode, nested, schemaConfig, context) }

        result = result and createWhere(node, where.node, dslNode, schemaConfig = schemaConfig, queryContext = context)

        result = result and createWhere(
            null,
            where.jwt,
            Cypher.parameter(Constants.JWT, context.auth?.jwt),
            null,
            schemaConfig,
            context,
            addNotNullCheck = true
        )

        return result
    }
}
