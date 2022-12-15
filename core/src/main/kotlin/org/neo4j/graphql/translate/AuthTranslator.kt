package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Conditions
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Predicates
import org.neo4j.graphql.*
import org.neo4j.graphql.Constants.AUTH_UNAUTHENTICATED_ERROR
import org.neo4j.graphql.Constants.OR
import org.neo4j.graphql.Constants.PREDICATE_JOINS
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.handler.utils.ChainString.Companion.extend

class AuthTranslator(
    val schemaConfig: SchemaConfig,
    val queryContext: QueryContext?,

    val skipRoles: Boolean = false,
    val skipIsAuthenticated: Boolean = false,
    val allow: AuthOptions? = null, // TODO harmonize args
    val bind: AuthOptions? = null,
    val where: AuthOptions? = null,
) {

    fun createAuth(auth: AuthDirective?, vararg operations: AuthDirective.AuthOperation) =
        createAuth(auth, setOf(*operations))

    fun createAuth(auth: AuthDirective?, operations: Set<AuthDirective.AuthOperation>): Condition? {
        if (auth == null) {
            return null
        }
        val authRules = if (operations.isNotEmpty()) {
            auth.rules.filter { it.operations.isNullOrEmpty() || it.operations.containsAny(operations) }
        } else {
            auth.rules
        }

        if (where != null && !auth.hasWhereRule()) {
            return null
        }

        var condition: Condition? = null

        authRules.forEachIndexed { index, authRule ->
            createSubPredicate(authRule, index)?.let { condition = condition or it }
        }

        return condition
    }

    private fun createSubPredicate(
        authRule: AuthDirective.BaseAuthRule,
        index: Int,
        chainStr: ChainString? = null
    ): Condition? {
        var condition: Condition? = createRolesCondition(authRule)

        createIsAuthenticatedCondition(authRule)?.let { condition = condition and it }

        if (allow != null && authRule.allow != null) {
            createAuthPredicate(
                authRule.allow,
                useAnyPredicate = true,
                node = allow.parentNode,
                varName = allow.varName,
                chainStr = (allow.chainStr ?: ChainString(schemaConfig, allow.varName))
                    .extend(chainStr, "auth", "allow", index),
            )
                ?.let { condition = condition and it }

        }

        if (bind != null && authRule.bind != null) {
            createAuthPredicate(
                authRule.bind,
                useAnyPredicate = false,
                node = bind.parentNode,
                varName = bind.varName,
                chainStr = (bind.chainStr ?: ChainString(schemaConfig, bind.varName))
                    .extend(chainStr, "auth", "bind", index),
            )
                ?.let { condition = condition and it }
        }

        if (where != null && authRule.where != null) {
            createAuthPredicate(
                authRule.where,
                useAnyPredicate = false,
                node = where.parentNode,
                varName = where.varName,
                chainStr = (where.chainStr ?: ChainString(schemaConfig, where.varName))
                    .extend(chainStr, "auth", "where", index),
            )
                ?.let { condition = condition and it }
        }

        var ors: Condition? = null
        authRule.OR?.forEachIndexed { i, nestedRule ->
            createSubPredicate(nestedRule, i, chainStr.extend(schemaConfig, "OR", i))
                ?.let { ors = ors or it }
        }
        if (ors != null) {
            condition = condition?.and(ors) ?: ors
        }

        authRule.AND?.forEachIndexed { i, nestedRule ->
            createSubPredicate(nestedRule, i, chainStr.extend(schemaConfig, "AND", i))
                ?.let { condition = condition and it }
        }

        return condition
    }

    private fun createRolesCondition(authRule: AuthDirective.BaseAuthRule): Condition? {
        if (skipRoles || authRule.roles.isNullOrEmpty()) {
            return null
        }

        val r = Cypher.name("r")
        val rr = Cypher.name("rr")
        return Predicates.any(r)
            .`in`(Cypher.listOf(authRule.roles.map { it.asCypherLiteral() })) // TODO should we provide the list as param?
            .where(
                Predicates.any("rr")
                    .`in`(Cypher.parameter("auth.roles", queryContext?.auth))
                    .where(r.eq(rr))
            )
    }

    private fun createIsAuthenticatedCondition(authRule: AuthDirective.BaseAuthRule): Condition? {
        if (skipIsAuthenticated || authRule.isAuthenticated == null) {
            return null
        }
        // TODO can we optimize this apoc call?
        return Cypher.call("apoc.util.validatePredicate")
            .withArgs(
                Cypher.parameter("auth.isAuthenticated", queryContext?.auth) // TODO optimize compile time check
                    .eq(authRule.isAuthenticated.asCypherLiteral()).not(),
                AUTH_UNAUTHENTICATED_ERROR.asCypherLiteral(),
                Cypher.listOf(0.asCypherLiteral())
            )
            .asFunction()
            .asCondition()

    }

    private fun createAuthPredicate(
        ruleDefinitions: Map<*, *>,
        allowUnauthenticated: Boolean? = null,
        useAnyPredicate: Boolean,
        chainStr: ChainString,
        node: Node,
        varName: org.neo4j.cypherdsl.core.Node
    ): Condition? {
        var condition: Condition? = null

        ruleDefinitions.forEach { (keyObject, value) ->
            val key = keyObject as String
            if (PREDICATE_JOINS.contains(key)) {
                var innerConditions: Condition? = null
                (value as? List<*>)?.forEachIndexed { index, item ->
                    if (item !is Map<*, *>) {
                        return@forEachIndexed
                    }
                    createAuthPredicate(
                        item,
                        allowUnauthenticated,
                        useAnyPredicate,
                        chainStr.extend(key, index),
                        node,
                        varName
                    )?.let { innerCondition ->
                        innerConditions = innerConditions
                            ?.let {
                                if (key == OR) {
                                    it.or(innerCondition)
                                } else {
                                    it.and(innerCondition)
                                }
                            }
                            ?: innerCondition
                    }

                }
                if (innerConditions != null) {
                    condition = condition?.and(innerConditions) ?: innerConditions
                }
            }


            val authableField = node.authableFields.find { it.fieldName == key }
            if (authableField != null) {
                val paramValue = (value as? String)?.let { queryContext?.resolve(it) ?: it }

                if (paramValue == null && allowUnauthenticated != true) {
                    throw Neo4jGraphQLAuthenticationError("Unauthenticated")
                }

                val allowCondition = when (paramValue) {
                    null -> Conditions.isFalse() // todo undefined vs null
//                        null -> varName.property(authableField.dbPropertyName).isNull
                    else -> {
                        val property = varName.property(authableField.dbPropertyName)
                        val parameter = chainStr.extend(key).resolveParameter(paramValue)
                        property.isNotNull.and(property.eq(parameter))
                    }
                }
                allowCondition?.let { condition = condition and it }
            }

            val relationField = node.relationFields.find { it.fieldName == key }
            if (relationField != null) {
                val refNode = relationField.node ?: return@forEach

                val end = Cypher.node(refNode.mainLabel, refNode.additionalLabels(queryContext))
                val namedEnd = end.named(relationField.fieldName)

                var authPredicate = Conditions.noCondition()
                (value as Map<*, *>).forEach { (k, v) ->
                    authPredicate = authPredicate.and(
                        createAuthPredicate(
                            mapOf(k to v),
                            allowUnauthenticated,
                            useAnyPredicate,
                            chainStr.extend(key),
                            refNode,
                            namedEnd
                        )
                    )
                }
//                TODO use this
//                val  cond = Cypher.name("cond")
                val cond = Cypher.name(namedEnd.name())
                val o = if (useAnyPredicate) {
                    Predicates.any(cond)
                } else {
                    Predicates.all(cond)
                }
//                TODO check if we can use this
//                    .`in`(Cypher.listBasedOn(relationField.createDslRelation(varName, namedEnd)).returning(authPredicate))
//                    .where(cond.asCondition())
                    .`in`(Cypher.listBasedOn(relationField.createDslRelation(varName, namedEnd)).returning(cond))
                    .where(authPredicate)

                val relationCondition = Predicates.exists(relationField.createDslRelation(varName, end)).and(o)
                condition = condition?.and(relationCondition) ?: relationCondition
            }
        }

        return condition
    }

    data class AuthOptions(
        val varName: org.neo4j.cypherdsl.core.Node,
        val parentNode: Node,
        val chainStr: ChainString? = null
    )
}
