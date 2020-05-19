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
import org.slf4j.LoggerFactory

class OptimizedFilterHandler(val type: GraphQLFieldsContainer) {

    fun generateFilterQuery(variable: String, field: Field): Pair<StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere?, MutableMap<String, Any?>> {
        if (type.isRelationType()) {
            throw OptimizedQueryException("Optimization for relation not implemented, please provide a test case")
        }
        val rootNode = Cypher.node(type.label(quote = false)).named(variable)

        val readingWithoutWhere = Cypher.match(rootNode)
        var withWithoutWhere: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere? = null
        val filterParams = mutableMapOf<String, Any?>()

        for (argument in field.arguments) {
            if (argument.name == ProjectionBase.FILTER) {
                val parsedQuery = ParsedQuery(argument.value as ObjectValue, type)
                withWithoutWhere = NestingLevelHandler(parsedQuery, false, rootNode, variable, { readingWithoutWhere },
                        type, argument.value, filterParams, linkedSetOf(rootNode.requiredSymbolicName))
                    .parseFilter()
            } else {
                throw OptimizedQueryException()
            }
        }
        return withWithoutWhere to filterParams
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
            private val readingWithoutWhereFactory: () -> StatementBuilder.OngoingReading,
            private val type: GraphQLFieldsContainer,
            private val value: Value<*>?,
            private val filterParams: MutableMap<String, Any?>,
            private val parentPassThroughWiths: Collection<Expression>
    ) {

        /**
         * @param additionalFilter additional filters to be applied to the where
         */
        fun parseFilter(additionalFilter: ((StatementBuilder.ExposesWith) -> StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere)? = null): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
            if (value !is ObjectValue) {
                throw OptimizedQueryException()
            }
            // WHERE MATCH all predicates for current
            // WITH x
            var query = addWhere(additionalFilter)

            // Handle all quantifier (ALL / NONE / SINGLE / ANY)
            query = handleRelation(query)

            // Handle OR / AND
            return handleCombinations(query)
        }

        private fun addWhere(
                additionalFilter: ((StatementBuilder.ExposesWith) -> StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere)? = null
        ): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
            val reading = readingWithoutWhereFactory()
            if (parsedQuery.fieldPredicates.isEmpty()) {
                if (reading is StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere) {
                    return reading
                }
            }
            if (reading !is StatementBuilder.OngoingReadingWithoutWhere) {
                throw IllegalStateException("cannot handle " + reading::class.java)
            }

            // WHERE MATCH all predicates for current
            val readingWithWhere = addConditions<StatementBuilder.OngoingReadingWithWhere>(current, variablePrefix, parsedQuery.fieldPredicates, filterParams) { where, condition ->
                where?.and(condition) ?: reading.where(condition)
            }
            return if (additionalFilter != null) {
                additionalFilter(readingWithWhere ?: reading)
            } else {
                val withs = if (parsedQuery.relationPredicates.isNotEmpty() && parentPassThroughWiths.firstOrNull { it == current.requiredSymbolicName } == null) {
                    parentPassThroughWiths + current.requiredSymbolicName
                } else {
                    parentPassThroughWiths
                }
                withClauseWithOptionalDistinct(readingWithWhere ?: reading, withs, useDistinct)
            }
        }

        private fun handleRelation(passedQuery: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
            var query = passedQuery

            // WITHs to pass through for this depth
            val levelPassThroughWiths = parentPassThroughWiths.toCollection(LinkedHashSet())
            for ((index, relFilter) in parsedQuery.relationPredicates.withIndex()) {

                val (op, objectField, relField) = relFilter
                val rel = type.relationshipFor(relField.name)!!
                val label = (relField.type.inner() as? GraphQLObjectType)?.label(quote = false)!!
                val relNode = Cypher.node(label)

                if (objectField.value is NullValue) {
                    // EXISTS + NOT EXISTS
                    if (current !is Node) {
                        throw OptimizedQueryException()
                    }

                    val relation = createRelation(rel, current, relNode)
                    val where = when (op) {
                        RelationOperator.NOT -> query.where(relation)
                        RelationOperator.EQ_OR_NOT_EXISTS -> query.where(Conditions.not(relation))
                        else -> throw IllegalStateException("$op should not be set for Null value")
                    }
                    query = withClauseWithOptionalDistinct(where, levelPassThroughWiths)
                    continue
                }
                if (objectField.value !is ObjectValue) {
                    throw OptimizedQueryException()
                }

                val nestedParsedQuery = ParsedQuery(objectField.value as ObjectValue, relField.type.getInnerFieldsContainer())
                val hasPredicates = nestedParsedQuery.fieldPredicates.isNotEmpty() || nestedParsedQuery.relationPredicates.isNotEmpty()
//                if (!hasPredicates) {
//                    continue
//                }

                val relVariableName = variablePrefix + "_" + objectField.name
                val relVariable = relNode.named(relVariableName)

                if (index + 1 < parsedQuery.relationPredicates.size) {
                    // if there are additional relationPredicates, we need to pass through the current
                    levelPassThroughWiths.add(current.requiredSymbolicName)
                }

                val nestedRelationship = { target: Node ->
                    createRelation(rel, current as? Node ?: throw OptimizedQueryException(), target)
                }
                val nestingLevelHandler = NestingLevelHandler(
                        nestedParsedQuery,
                        true,
                        relVariable,
                        relVariableName,
                        { if (hasPredicates) query.match(nestedRelationship(relVariable)) else query },
                        rel.type,
                        objectField.value,
                        filterParams,
                        levelPassThroughWiths)

                // used as additional filter to handle SINGLE and EVERY use cases
                val totalAndCountFilter = { whereClause: (withWithoutWhere: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere, totalVar: SymbolicName, countVar: SymbolicName) -> StatementBuilder.OrderableOngoingReadingAndWithWithWhere ->
                    { exposesWith: StatementBuilder.ExposesWith ->
                        val totalRel = createRelation(rel, current as? Node ?: throw OptimizedQueryException(), relNode)
                        val totalVar = relVariableName + "_total"
                        val total = Functions.size(totalRel).`as`(totalVar)
                        val countVar = relVariableName + "_count"
                        val count = Functions.countDistinct(relVariable).`as`(countVar)
                        var additionalWiths = emptyList<SymbolicName>()
                        if (nestedParsedQuery.relationPredicates.isNotEmpty()) {
                            additionalWiths = listOf(relVariable.requiredSymbolicName)
                        }

                        val withWithoutWhere = withClauseWithOptionalDistinct(
                                exposesWith,
                                levelPassThroughWiths + additionalWiths + listOf(total, count)
                        )
                        val where = whereClause(withWithoutWhere, Cypher.name(totalVar), Cypher.name(countVar))
                        withClauseWithOptionalDistinct(where, levelPassThroughWiths + additionalWiths)
                    }
                }

                when (op) {
                    RelationOperator.SOME -> query = nestingLevelHandler.parseFilter()
                    RelationOperator.EVERY -> query = nestingLevelHandler.parseFilter(totalAndCountFilter({ withWithoutWhere, total, count ->
                        withWithoutWhere.where(total.isEqualTo(count))
                    }))
                    RelationOperator.SINGLE -> query = nestingLevelHandler.parseFilter(totalAndCountFilter({ withWithoutWhere, total, count ->
                        withWithoutWhere
                            .where(total.isEqualTo(count))
                            .and(total.isEqualTo(Cypher.literalOf(1)))
                    }))
                    RelationOperator.NONE -> {
                        val patternWithoutWhere = Cypher.listBasedOn(createRelation(rel, current as? Node
                                ?: throw OptimizedQueryException(), relVariable))
                        if (nestedParsedQuery.and.isNullOrEmpty() && nestedParsedQuery.or.isNullOrEmpty() && nestedParsedQuery.relationPredicates.isNullOrEmpty()) {
                            if (nestedParsedQuery.fieldPredicates.isNotEmpty()) {
                                val patternWithoutReturn = addConditions<PatternComprehension.OngoingDefinitionWithoutReturn>(
                                        relVariable, relVariableName, nestedParsedQuery.fieldPredicates, filterParams) { where, condition ->
                                    where?.and(condition) ?: patternWithoutWhere.where(condition)
                                } ?: patternWithoutWhere
                                val where = query
                                    .where(Functions.size(patternWithoutReturn.returning(Cypher.literalTrue())).isEqualTo(Cypher.literalOf(0)))
                                query = withClauseWithOptionalDistinct(where, levelPassThroughWiths)
                            }
                        } else {
                            throw OptimizedQueryException()
                        }
                    }
                    else -> throw IllegalStateException("$op should not be set for relationPredicates")
                }
            }
            return query
        }

        private fun handleCombinations(passedQuery: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
            if ( parsedQuery.or.isNullOrEmpty() && parsedQuery.and.isNullOrEmpty()){
                return passedQuery
            }
            throw OptimizedQueryException()
        }

        private fun withClauseWithOptionalDistinct(
                exposesWith: StatementBuilder.ExposesWith,
                withs: Collection<Expression>,
                useDistinct: Boolean = true): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere =
                when {
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
                val (predicate, objectField, field) = conditionField
                val prop = propertyContainer.property(field.name)
                val parameter = Cypher.parameter(variablePrefix + "_" + objectField.name)
                val condition = predicate.conditionCreator(prop, parameter)
                filterParams[parameter.name] = objectField.value.toJavaValue()
                where = conditionAdder(where, condition)
            }
            return where
        }


        private fun createRelation(rel: RelationshipInfo, start: Node, end: Node): Relationship =
                when (rel.out) {
                    false -> start.relationshipFrom(end, rel.relType)
                    true -> start.relationshipTo(end, rel.relType)
                    null -> start.relationshipBetween(end, rel.relType)
                }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(OptimizedFilterHandler::class.java)

        data class Predicate<T>(
                val op: T,
                val queryField: ObjectField,
                val fieldDefinition: GraphQLFieldDefinition,
                val index: Int)

        class ParsedQuery(objectValue: ObjectValue, type: GraphQLFieldsContainer) {
            val fieldPredicates = mutableListOf<Predicate<FieldOperator>>()
            val relationPredicates = mutableListOf<Predicate<RelationOperator>>()
            val or: List<Value<*>>?
            val and: List<Value<*>>?

            init {
                val queriedFields = objectValue.objectFields
                    .mapIndexed { index, field -> field.name to (index to field) }
                    .toMap(mutableMapOf())
                or = queriedFields.remove("OR")?.second?.value?.let {
                    return@let (it as ArrayValue).values
                            ?: throw IllegalArgumentException("OR is expected to be a list")
                }
                and = queriedFields.remove("AND")?.second?.value?.let {
                    return@let (it as ArrayValue).values
                            ?: throw IllegalArgumentException("AND is expected to be a list")
                }
                for (definedField in type.fieldDefinitions) {
                    if (definedField.isRelationship()) {
                        collectRelationPredicates(type, definedField, queriedFields)
                    } else {
                        collectFieldPredicates(definedField, queriedFields)
                    }
                }
                if (queriedFields.isNotEmpty()) {
                    throw OptimizedQueryException("queried unknown fields: " + queriedFields.keys)
                }
                fieldPredicates.sortedBy(Predicate<FieldOperator>::index)
                relationPredicates.sortedBy(Predicate<RelationOperator>::index)
            }

            private fun collectRelationPredicates(type: GraphQLFieldsContainer, definedField: GraphQLFieldDefinition, queriedFields: MutableMap<String, Pair<Int, ObjectField>>) {
                RelationOperator.values()
                    .map { it to definedField.name + it.suffix }
                    .mapNotNull { (queryOp, queryFieldName) ->
                        val (index, objectField) = queriedFields.remove(queryFieldName) ?: return@mapNotNull null
                        val op = when (definedField.type.isList()) {
                            true -> when (queryOp) {
                                RelationOperator.NOT -> when (objectField.value) {
                                    is NullValue -> RelationOperator.NOT
                                    else -> RelationOperator.NONE
                                }
                                RelationOperator.EQ_OR_NOT_EXISTS -> when (objectField.value) {
                                    is NullValue -> RelationOperator.EQ_OR_NOT_EXISTS
                                    else -> {
                                        LOGGER.info("$queryFieldName on type ${type.name} was used for filtering, consider using ${definedField.name}${RelationOperator.EVERY.suffix} instead")
                                        RelationOperator.EVERY
                                    }
                                }
                                else -> queryOp
                            }
                            false -> when (queryOp) {
                                RelationOperator.SINGLE -> {
                                    LOGGER.warn("Using $queryFieldName on type ${type.name} is deprecated, use ${definedField.name} directly")
                                    RelationOperator.SOME
                                }
                                RelationOperator.SOME -> {
                                    LOGGER.warn("Using $queryFieldName on type ${type.name} is deprecated, use ${definedField.name} directly")
                                    RelationOperator.SOME
                                }
                                RelationOperator.NONE -> {
                                    LOGGER.warn("Using $queryFieldName on type ${type.name} is deprecated, use ${definedField.name}${RelationOperator.NOT.suffix} instead")
                                    RelationOperator.NONE
                                }
                                RelationOperator.NOT -> when (objectField.value) {
                                    is NullValue -> RelationOperator.NOT
                                    else -> RelationOperator.NONE
                                }
                                RelationOperator.EQ_OR_NOT_EXISTS -> when (objectField.value) {
                                    is NullValue -> RelationOperator.EQ_OR_NOT_EXISTS
                                    else -> RelationOperator.SOME
                                }
                                else -> queryOp
                            }
                        }
                        Predicate(op, objectField, definedField, index)
                    }
                    .forEach { relationPredicates.add(it) }
            }

            private fun collectFieldPredicates(definedField: GraphQLFieldDefinition, queriedFields: MutableMap<String, Pair<Int, ObjectField>>) {
                FieldOperator.values()
                    .map { it to definedField.name + it.suffix }
                    .mapNotNull { (predicate, queryFieldName) ->
                        val (index, objectField) = queriedFields.remove(queryFieldName) ?: return@mapNotNull null
                        Predicate(predicate, objectField, definedField, index)
                    }
                    .forEach { fieldPredicates.add(it) }
            }
        }
    }


}