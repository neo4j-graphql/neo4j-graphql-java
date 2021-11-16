package org.neo4j.graphql.handler.filter

import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.StatementBuilder.*
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.projection.ProjectionBase
import org.neo4j.graphql.parser.ParsedQuery
import org.neo4j.graphql.parser.QueryParser
import org.neo4j.graphql.parser.QueryParser.parseFilter
import org.neo4j.graphql.parser.RelationPredicate


typealias WhereClauseFactory = (
        queryWithoutWhere: OrderableOngoingReadingAndWithWithoutWhere,
        names: List<SymbolicName>
) -> OrderableOngoingReadingAndWithWithWhere

typealias ConditionBuilder = (ExposesWith) -> OrderableOngoingReadingAndWithWithoutWhere

/**
 * This is a specialized handler that uses an alternative approach for filtering. By using multiple MATCH clauses,
 * this can facilitate the use of optimizations within the neo4j database, which can lead to significant performance
 * improvements for large data sets.
 *
 * If this handler cannot generate an optimization for the passed filter, an [OptimizedQueryException] will be
 * thrown, so the calling site can fall back to the non-optimized logic
 */
class OptimizedFilterHandler(val type: GraphQLFieldsContainer, schemaConfig: SchemaConfig) : ProjectionBase(schemaConfig) {

    fun generateFilterQuery(variable: String, fieldDefinition: GraphQLFieldDefinition, arguments: Map<String, Any>, readingWithoutWhere: OngoingReadingWithoutWhere, rootNode: PropertyContainer, variables: Map<String, Any>): OngoingReading {
        if (type.isRelationType()) {
            throw OptimizedQueryException("Optimization for relationship entity type is not implemented. Please provide a test case to help adding further cases.")
        }

        var ongoingReading: OngoingReading? = null

        if (!schemaConfig.useWhereFilter) {
            val filteredArguments = arguments.filterKeys { !SPECIAL_FIELDS.contains(it) }
            if (filteredArguments.isNotEmpty()) {
                val parsedQuery = QueryParser.parseArguments(filteredArguments, fieldDefinition, type)
                val condition = handleQuery(variable, "", rootNode, parsedQuery, type, variables)
                ongoingReading = readingWithoutWhere.where(condition)
            }
        }
        return arguments[filterFieldName()]
            ?.let { it as Map<*, *> }
            ?.let {
                val parsedQuery = parseFilter(it, type)
                NestingLevelHandler(parsedQuery, false, rootNode, variable, ongoingReading
                        ?: readingWithoutWhere,
                        type, it, linkedSetOf(rootNode.requiredSymbolicName), variables)
                    .parseFilter()
            }
                ?: readingWithoutWhere
    }

    /**
     * @param parsedQuery the internal representation of the parsed query for this nesting level
     * @param useDistinct should the current node be distinct (if true: renders WITH DISTINCT currentNode)
     * @param current the current node
     * @param variablePrefix the prefix to prepend to new variables
     * @param type the type of <code>current</code>
     * @param value the value passed to the graphQL field
     * @param parentPassThroughWiths all the nodes, required to be passed through via WITH
     */
    inner class NestingLevelHandler(
            private val parsedQuery: ParsedQuery,
            private val useDistinct: Boolean,
            private val current: PropertyContainer,
            private val variablePrefix: String,
            private val matchQueryWithoutWhere: OngoingReading,
            private val type: GraphQLFieldsContainer,
            private val value: Map<*, *>,
            private val parentPassThroughWiths: Collection<Expression>,
            private val variables: Map<String, Any>
    ) {

        private fun currentNode() = current as? Node
                ?: throw OptimizedQueryException("Only filtering on nodes is currently supported by the OptimizedFilterHandler. Please provide a test case to help adding further cases.")

        /**
         * @param additionalConditions additional conditions to be applied to the where
         */
        fun parseFilter(additionalConditions: ConditionBuilder? = null): OrderableOngoingReadingAndWithWithoutWhere {
            // WHERE MATCH all predicates for current
            // WITH x
            var query = addWhere(additionalConditions)

            // Handle all quantifier (ALL / NONE / SINGLE / ANY)
            query = handleQuantifier(query)

            // Handle OR / AND
            return handleCombinations(query)
        }

        private fun addWhere(additionalConditions: ConditionBuilder? = null): OrderableOngoingReadingAndWithWithoutWhere {
            if (parsedQuery.fieldPredicates.isEmpty()) {
                if (matchQueryWithoutWhere is OrderableOngoingReadingAndWithWithoutWhere) {
                    return matchQueryWithoutWhere
                }
            }
            if (matchQueryWithoutWhere !is OngoingReadingWithoutWhere) {
                throw IllegalStateException("Expect to have a query without where, but we got " + matchQueryWithoutWhere::class.java + " which cannot be handled")
            }

            // WHERE MATCH all predicates for current
            val condition = parsedQuery.getFieldConditions(current, variablePrefix, "", schemaConfig)
            val matchQueryWithWhere = matchQueryWithoutWhere.where(condition)

            return if (additionalConditions != null) {
                additionalConditions(matchQueryWithWhere)
            } else {
                val withs = if (parsedQuery.relationPredicates.isNotEmpty() && parentPassThroughWiths.none { it == current.requiredSymbolicName }) {
                    parentPassThroughWiths + current.requiredSymbolicName
                } else {
                    parentPassThroughWiths
                }
                withClauseWithOptionalDistinct(matchQueryWithWhere, withs, useDistinct)
            }
        }

        private fun handleQuantifier(passedQuery: OrderableOngoingReadingAndWithWithoutWhere): OrderableOngoingReadingAndWithWithoutWhere {
            var query = passedQuery

            // WITHs to pass through for this depth
            val levelPassThroughWiths = parentPassThroughWiths.toCollection(LinkedHashSet())
            for ((index, relFilter) in parsedQuery.relationPredicates.withIndex()) {

                val value = relFilter.value

                if (value == null) {
                    // EXISTS + NOT EXISTS
                    val existsCondition = relFilter.createExistsCondition(currentNode())
                    query = withClauseWithOptionalDistinct(query.where(existsCondition), levelPassThroughWiths)
                    continue
                }
                if (value !is Map<*, *>) {
                    throw IllegalArgumentException("Only object values are supported by the OptimizedFilterHandler")
                }

                if (index + 1 == parsedQuery.relationPredicates.size) {
                    levelPassThroughWiths.retainAll(parentPassThroughWiths)
                } else {
                    // if there are additional relationPredicates, we need to pass through the current
                    levelPassThroughWiths.add(current.requiredSymbolicName)
                }

                query = handleQuantifierPredicates(query, relFilter, levelPassThroughWiths)
            }
            return query
        }

        private fun handleQuantifierPredicates(
                query: OrderableOngoingReadingAndWithWithoutWhere,
                relFilter: RelationPredicate,
                levelPassThroughWiths: LinkedHashSet<Expression>
        ): OrderableOngoingReadingAndWithWithoutWhere {
            val objectField = relFilter.value
            val nestedParsedQuery = parseFilter(objectField as Map<*, *>, relFilter.fieldDefinition.type.getInnerFieldsContainer())
            val hasPredicates = nestedParsedQuery.fieldPredicates.isNotEmpty() || nestedParsedQuery.relationPredicates.isNotEmpty()

            var queryWithoutWhere = query
            val relVariableName = normalizeName(variablePrefix, relFilter.normalizedName)
            val relVariable = relFilter.relNode.named(relVariableName)
            val readingWithoutWhere: OngoingReading = when (relFilter.op) {
                RelationOperator.NONE -> queryWithoutWhere.optionalMatch(relFilter.relationshipInfo.createRelation(currentNode(), relVariable))
                else -> when (hasPredicates) {
                    true -> queryWithoutWhere.match(relFilter.relationshipInfo.createRelation(currentNode(), relVariable))
                    else -> queryWithoutWhere
                }
            }

            val totalFilter = { totalFilter(relFilter, relVariableName) }
            val countFilter = { countFilter(relVariable, relVariableName) }
            val additionalConditions = { filter: List<Pair<SymbolicName, AliasedExpression>>, whereClauseFactory: WhereClauseFactory ->
                createAdditionalConditions(nestedParsedQuery, relVariable, levelPassThroughWiths, filter, whereClauseFactory)
            }

            val nestingLevelHandler = NestingLevelHandler(nestedParsedQuery, true, relVariable, relVariableName,
                    readingWithoutWhere, relFilter.relationshipInfo.type, objectField, levelPassThroughWiths, variables)

            when (relFilter.op) {
                RelationOperator.SOME -> queryWithoutWhere = nestingLevelHandler.parseFilter()
                RelationOperator.EVERY -> queryWithoutWhere = nestingLevelHandler.parseFilter(
                        additionalConditions(listOf(totalFilter(), countFilter()), { withWithoutWhere, (total, count) ->
                            withWithoutWhere.where(total.isEqualTo(count))
                        }))
                RelationOperator.SINGLE -> queryWithoutWhere = nestingLevelHandler.parseFilter(
                        additionalConditions(listOf(totalFilter(), countFilter()), { withWithoutWhere, (total, count) ->
                            withWithoutWhere
                                .where(total.isEqualTo(count))
                                .and(total.isEqualTo(Cypher.literalOf<Number>(1)))
                        }))
                RelationOperator.NONE -> queryWithoutWhere = nestingLevelHandler.parseFilter(
                        additionalConditions(listOf(countFilter()), { withWithoutWhere, (count) ->
                            withWithoutWhere
                                .where(count.isEqualTo(Cypher.literalOf<Number>(0)))
                        }))
                else -> throw IllegalStateException("${relFilter.op} should not be set for filed `${relFilter.fieldDefinition.name}` of type `$type`.")
            }
            return queryWithoutWhere
        }

        private fun handleCombinations(passedQuery: OrderableOngoingReadingAndWithWithoutWhere): OrderableOngoingReadingAndWithWithoutWhere {
            if (parsedQuery.or.isNullOrEmpty() && parsedQuery.and.isNullOrEmpty()) {
                return passedQuery
            }
            throw OptimizedQueryException("AND / OR filters are currently not implemented. Please provide a test case to help adding further cases.")
        }

        private fun totalFilter(relationPredicate: RelationPredicate, relVariableName: String): Pair<SymbolicName, AliasedExpression> {
            val totalRel = relationPredicate.relationshipInfo.createRelation(currentNode(), relationPredicate.relNode)
            val totalVar = normalizeName(relVariableName, "Total")
            val total = Functions.size(totalRel).`as`(totalVar)
            return Cypher.name(totalVar) to total
        }

        private fun countFilter(relVariable: Node, relVariableName: String): Pair<SymbolicName, AliasedExpression> {
            val countVar = normalizeName(relVariableName, "Count")
            val count = Functions.countDistinct(relVariable).`as`(countVar)
            return Cypher.name(countVar) to count
        }

        private fun createAdditionalConditions(
                query: ParsedQuery,
                relVariable: Node,
                passThroughWiths: LinkedHashSet<Expression>,
                filter: List<Pair<SymbolicName, AliasedExpression>>,
                whereClauseFactory: WhereClauseFactory
        ): ConditionBuilder {
            return { exposesWith: ExposesWith ->
                var additionalWiths = emptyList<SymbolicName>()
                if (query.relationPredicates.isNotEmpty()) {
                    additionalWiths = listOf(relVariable.requiredSymbolicName)
                }

                val withWithoutWhere = withClauseWithOptionalDistinct(
                        exposesWith,
                        passThroughWiths + additionalWiths + filter.map { it.second }
                )
                val where = whereClauseFactory(withWithoutWhere, filter.map { it.first })
                withClauseWithOptionalDistinct(where, passThroughWiths + additionalWiths)
            }

        }

        private fun withClauseWithOptionalDistinct(
                exposesWith: ExposesWith,
                withs: Collection<Expression>,
                useDistinct: Boolean = true) = when {
            useDistinct && withs.size == 1 -> exposesWith.withDistinct(*withs.toTypedArray())
            else -> exposesWith.with(*withs.toTypedArray())
        }

    }
}
