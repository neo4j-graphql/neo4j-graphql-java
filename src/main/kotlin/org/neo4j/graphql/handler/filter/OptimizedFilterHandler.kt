package org.neo4j.graphql.handler.filter

import graphql.language.*
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLObjectType
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.projection.ProjectionBase
import org.neo4j.opencypherdsl.*
import org.neo4j.opencypherdsl.Cypher
import org.neo4j.opencypherdsl.Node


typealias WhereClauseFactory = (
        queryWithoutWhere: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere,
        names: List<SymbolicName>
) -> StatementBuilder.OrderableOngoingReadingAndWithWithWhere

class OptimizedFilterHandler(val type: GraphQLFieldsContainer) {

    fun generateFilterQuery(variable: String, field: Field): Pair<StatementBuilder.OngoingReading, MutableMap<String, Any?>> {
        if (type.isRelationType()) {
            throw OptimizedQueryException("Optimization for relationship entity type is not implemented. Please provide a test case to help adding further cases.")
        }
        val rootNode = Cypher.node(type.label()).named(variable)

        val readingWithoutWhere = Cypher.match(rootNode)
        var withWithoutWhere: StatementBuilder.OngoingReading? = null
        val filterParams = mutableMapOf<String, Any?>()

        for (argument in field.arguments) {
            if (argument.name == ProjectionBase.FILTER) {
                val parsedQuery = ParsedQuery(argument.value as ObjectValue, type)
                withWithoutWhere = NestingLevelHandler(parsedQuery, false, rootNode, variable, readingWithoutWhere,
                        type, argument.value, filterParams, linkedSetOf(rootNode.requiredSymbolicName))
                    .parseFilter()
            } else {
                throw OptimizedQueryException("Querying without filter is not subject to the OptimizedFilterHandler")
            }
        }
        return (withWithoutWhere ?: readingWithoutWhere) to filterParams
    }

    /**
     * @param parsedQuery the internal representation of the parsed query for this nesting level
     * @param useDistinct should the current node be distinct (if true: renders WITH DISTINCT currentNode)
     * @param current the current node
     * @param variablePrefix the prefix to prepend to new variables
     * @param type the type of <code>current</code>
     * @param value the value passed to the graphQL field
     * @param filterParams the map to store required filter params into
     * @param parentPassThroughWiths all the nodes, required to be passed through via WITH
     */
    class NestingLevelHandler(
            private val parsedQuery: ParsedQuery,
            private val useDistinct: Boolean,
            private val current: PropertyContainer<*>,
            private val variablePrefix: String,
            private val matchQueryWithoutWhere: StatementBuilder.OngoingReading,
            private val type: GraphQLFieldsContainer,
            private val value: Value<*>?,
            private val filterParams: MutableMap<String, Any?>,
            private val parentPassThroughWiths: Collection<Expression>
    ) {

        private fun currentNode() = current as? Node
                ?: throw OptimizedQueryException("Only filtering on nodes is currently supported by the OptimizedFilterHandler. Please provide a test case to help adding further cases.")

        /**
         * @param additionalFilter additional filters to be applied to the where
         */
        fun parseFilter(additionalFilter: ((StatementBuilder.ExposesWith) -> StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere)? = null): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
            if (value !is ObjectValue) {
                throw IllegalArgumentException("Only object values are supported by the OptimizedFilterHandler")
            }
            // WHERE MATCH all predicates for current
            // WITH x
            var query = addWhere(additionalFilter)

            // Handle all quantifier (ALL / NONE / SINGLE / ANY)
            query = handleQuantifier(query)

            // Handle OR / AND
            return handleCombinations(query)
        }

        private fun addWhere(
                additionalFilter: ((StatementBuilder.ExposesWith) -> StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere)? = null
        ): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
            if (parsedQuery.fieldPredicates.isEmpty()) {
                if (matchQueryWithoutWhere is StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere) {
                    return matchQueryWithoutWhere
                }
            }
            if (matchQueryWithoutWhere !is StatementBuilder.OngoingReadingWithoutWhere) {
                throw IllegalStateException("Expect to have a query without where, but we got " + matchQueryWithoutWhere::class.java + " which cannot be handled")
            }

            // WHERE MATCH all predicates for current
            val matchQueryWithWhere = addConditions<StatementBuilder.OngoingReadingWithWhere>(current, variablePrefix, parsedQuery.fieldPredicates, filterParams) { where, condition ->
                where?.and(condition) ?: matchQueryWithoutWhere.where(condition)
            }
            return if (additionalFilter != null) {
                additionalFilter(matchQueryWithWhere ?: matchQueryWithoutWhere)
            } else {
                val withs = if (parsedQuery.relationPredicates.isNotEmpty() && parentPassThroughWiths.firstOrNull { it == current.requiredSymbolicName } == null) {
                    parentPassThroughWiths + current.requiredSymbolicName
                } else {
                    parentPassThroughWiths
                }
                withClauseWithOptionalDistinct(matchQueryWithWhere ?: matchQueryWithoutWhere, withs, useDistinct)
            }
        }

        private fun handleQuantifier(passedQuery: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
            var query = passedQuery

            // WITHs to pass through for this depth
            val levelPassThroughWiths = parentPassThroughWiths.toCollection(LinkedHashSet())
            for ((index, relFilter) in parsedQuery.relationPredicates.withIndex()) {

                val objectField = relFilter.queryField

                if (objectField.value is NullValue) {
                    // EXISTS + NOT EXISTS
                    query = handleExist(query, relFilter, levelPassThroughWiths)
                    continue
                }
                if (objectField.value !is ObjectValue) {
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

        private fun handleExist(query: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere, relFilter: RelationPredicate, levelPassThroughWiths: LinkedHashSet<Expression>): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
            val relation = relFilter.createRelation(currentNode())
            val where = when (relFilter.op) {
                RelationOperator.NOT -> query.where(relation)
                RelationOperator.EQ_OR_NOT_EXISTS -> query.where(Conditions.not(relation))
                else -> throw IllegalStateException("${relFilter.op} should not be set for Null value")
            }
            return withClauseWithOptionalDistinct(where, levelPassThroughWiths)
        }

        private fun handleQuantifierPredicates(
                query: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere,
                relFilter: RelationPredicate,
                levelPassThroughWiths: LinkedHashSet<Expression>
        ): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
            val objectField = relFilter.queryField
            val nestedParsedQuery = ParsedQuery(objectField.value as ObjectValue, relFilter.fieldDefinition.type.getInnerFieldsContainer())
            val hasPredicates = nestedParsedQuery.fieldPredicates.isNotEmpty() || nestedParsedQuery.relationPredicates.isNotEmpty()

            var queryWithoutWhere = query
            val relVariableName = variablePrefix + "_" + objectField.name
            val relVariable = relFilter.relNode.named(relVariableName)
            val readingWithoutWhere: StatementBuilder.OngoingReading = when (relFilter.op) {
                RelationOperator.NONE -> queryWithoutWhere.optionalMatch(relFilter.relationshipInfo.createRelation(currentNode(), relVariable))
                else -> when (hasPredicates) {
                    true -> queryWithoutWhere.match(relFilter.relationshipInfo.createRelation(currentNode(), relVariable))
                    else -> queryWithoutWhere
                }
            }

            val totalFilter = { totalFilter(relFilter, relVariableName) }
            val countFilter = { countFilter(relVariable, relVariableName) }
            // used as additional filter to handle SINGLE and EVERY use cases
            val commonFilter = { filter: List<Pair<SymbolicName, AliasedExpression>>, whereClauseFactory: WhereClauseFactory ->
                commonFilter(nestedParsedQuery, relVariable, levelPassThroughWiths, filter, whereClauseFactory)
            }

            val nestingLevelHandler = NestingLevelHandler(nestedParsedQuery, true, relVariable, relVariableName,
                    readingWithoutWhere, relFilter.relationshipInfo.type, objectField.value, filterParams, levelPassThroughWiths)

            when (relFilter.op) {
                RelationOperator.SOME -> queryWithoutWhere = nestingLevelHandler.parseFilter()
                RelationOperator.EVERY -> queryWithoutWhere = nestingLevelHandler.parseFilter(
                        commonFilter(listOf(totalFilter(), countFilter()), { withWithoutWhere, (total, count) ->
                            withWithoutWhere.where(total.isEqualTo(count))
                        }))
                RelationOperator.SINGLE -> queryWithoutWhere = nestingLevelHandler.parseFilter(
                        commonFilter(listOf(totalFilter(), countFilter()), { withWithoutWhere, (total, count) ->
                            withWithoutWhere
                                .where(total.isEqualTo(count))
                                .and(total.isEqualTo(Cypher.literalOf(1)))
                        }))
                RelationOperator.NONE -> queryWithoutWhere = nestingLevelHandler.parseFilter(
                        commonFilter(listOf(countFilter()), { withWithoutWhere, (count) ->
                            withWithoutWhere
                                .where(count.isEqualTo(Cypher.literalOf(0)))
                        }))
                else -> throw IllegalStateException("${relFilter.op} should not be set for filed `${relFilter.fieldDefinition.name}` of type `$type`.")
            }
            return queryWithoutWhere
        }

        private fun handleCombinations(passedQuery: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
            if (parsedQuery.or.isNullOrEmpty() && parsedQuery.and.isNullOrEmpty()) {
                return passedQuery
            }
            throw OptimizedQueryException("AND / OR filters are currently not implemented. Please provide a test case to help adding further cases.")
        }

        private fun totalFilter(relationPredicate: RelationPredicate, relVariableName: String): Pair<SymbolicName, AliasedExpression> {
            val totalRel = relationPredicate.relationshipInfo.createRelation(currentNode(), relationPredicate.relNode)
            val totalVar = relVariableName + "_total"
            val total = Functions.size(totalRel).`as`(totalVar)
            return Cypher.name(totalVar) to total
        }

        private fun countFilter(relVariable: Node, relVariableName: String): Pair<SymbolicName, AliasedExpression> {
            val countVar = relVariableName + "_count"
            val count = Functions.countDistinct(relVariable).`as`(countVar)
            return Cypher.name(countVar) to count
        }

        private fun commonFilter(
                query: ParsedQuery,
                relVariable: Node,
                passThroughWiths: LinkedHashSet<Expression>,
                filter: List<Pair<SymbolicName, AliasedExpression>>,
                whereClauseFactory: WhereClauseFactory
        ): (StatementBuilder.ExposesWith) -> StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
            return { exposesWith: StatementBuilder.ExposesWith ->
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
                exposesWith: StatementBuilder.ExposesWith,
                withs: Collection<Expression>,
                useDistinct: Boolean = true) = when {
            useDistinct && withs.size == 1 -> exposesWith.withDistinct(*withs.toTypedArray())
            else -> exposesWith.with(*withs.toTypedArray())
        }

        private fun <WithWhere> addConditions(
                propertyContainer: PropertyContainer<*>,
                variablePrefix: String,
                conditions: List<Predicate<FieldOperator>>,
                filterParams: MutableMap<String, Any?>,
                conditionAdder: (where: WithWhere?, condition: Condition) -> WithWhere
        ): WithWhere? {
            var where: WithWhere? = null
            for (conditionField in conditions) {
                val prop = propertyContainer.property(conditionField.fieldDefinition.name)
                val parameter = Cypher.parameter(variablePrefix + "_" + conditionField.queryField.name)
                val condition = conditionField.op.conditionCreator(prop, parameter)
                filterParams[parameter.name] = conditionField.queryField.value.toJavaValue()
                where = conditionAdder(where, condition)
            }
            return where
        }

    }

    companion object {
        open class Predicate<T>(
                val op: T,
                val queryField: ObjectField,
                val fieldDefinition: GraphQLFieldDefinition,
                val index: Int)

        class RelationPredicate(
                type: GraphQLFieldsContainer,
                op: RelationOperator,
                queryField: ObjectField,
                fieldDefinition: GraphQLFieldDefinition,
                index: Int
        ) : Predicate<RelationOperator>(op, queryField, fieldDefinition, index) {

            val relationshipInfo = type.relationshipFor(fieldDefinition.name)!!
            val relNode: Node = Cypher.node((fieldDefinition.type.inner() as? GraphQLObjectType)?.label()!!)

            fun createRelation(start: Node): Relationship = relationshipInfo.createRelation(start, relNode)
        }


        class ParsedQuery(objectValue: ObjectValue, type: GraphQLFieldsContainer) {
            val fieldPredicates: List<Predicate<FieldOperator>>
            val relationPredicates: List<RelationPredicate>
            val or: List<Value<*>>?
            val and: List<Value<*>>?

            init {
                // Map of all queried fields
                // we remove all matching fields from this map, so we can ensure that only known fields got queried
                val queriedFields = objectValue.objectFields
                    .mapIndexed { index, field -> field.name to (index to field) }
                    .toMap(mutableMapOf())


                or = queriedFields.remove("OR")?.second?.value?.let {
                    (it as ArrayValue).values
                            ?: throw IllegalArgumentException("OR on type `${type.name}` is expected to be a list")
                }


                and = queriedFields.remove("AND")?.second?.value?.let {
                    (it as ArrayValue).values
                            ?: throw IllegalArgumentException("AND on type `${type.name}` is expected to be a list")
                }

                // find all matching fields
                val fieldPredicates = mutableListOf<Predicate<FieldOperator>>()
                val relationPredicates = mutableListOf<RelationPredicate>()
                for (definedField in type.fieldDefinitions) {
                    if (definedField.isRelationship()) {
                        RelationOperator.values()
                            .map { it to definedField.name + it.suffix }
                            .mapNotNull { (queryOp, queryFieldName) ->
                                queriedFields.remove(queryFieldName)?.let { (index, objectField) ->
                                    val harmonizedOperator = queryOp.harmonize(type, definedField, objectField.value, queryFieldName)
                                    RelationPredicate(type, harmonizedOperator, objectField, definedField, index)
                                }
                            }
                            .forEach { relationPredicates.add(it) }
                    } else {
                        FieldOperator.values()
                            .map { it to definedField.name + it.suffix }
                            .mapNotNull { (predicate, queryFieldName) ->
                                queriedFields.remove(queryFieldName)?.let { (index, objectField) ->
                                    Predicate(predicate, objectField, definedField, index)
                                }
                            }
                            .forEach { fieldPredicates.add(it) }
                    }
                }

                if (queriedFields.isNotEmpty()) {
                    throw IllegalArgumentException("queried unknown fields ${queriedFields.keys} on type ${type.name}")
                }

                this.fieldPredicates = fieldPredicates.sortedBy(Predicate<FieldOperator>::index)
                this.relationPredicates = relationPredicates.sortedBy(Predicate<RelationOperator>::index)
            }

        }
    }


}