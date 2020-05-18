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
    companion object {
        private val LOGGER = LoggerFactory.getLogger(OptimizedFilterHandler::class.java)
    }

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
                val queryInfo = parseQuery(argument.value as ObjectValue, type)
                withWithoutWhere = parseFilter(queryInfo, false, rootNode, variable, readingWithoutWhere,
                        type, argument.value, filterParams, linkedSetOf(rootNode.requiredSymbolicName), null)
            } else {
                throw OptimizedQueryException()
            }
        }
        return withWithoutWhere to filterParams
    }

    /**
     * 1. MATCH (parent)-[]-(current)
     * 2. WHERE MATCH all predicates for current
     * 3. WITH x
     * 4. Handle all quantifier (ALL / NONE / SINGLE / ANY)
     * 5. Handle OR / AND
     *
     * @param  parsedQuery the internal representation of the pares query for this nesting level
     * @param useDistinct should the current node be distinct (if true: renders WITH DISTINCT currentNode)
     * @param current the current node
     * @param variablePrefix the prefix to prepend to new variables
     * @param readingWithoutWhere the query to extend
     * @param type the type of <code>current</code>
     * @param value the value passed to the graphQL field
     * @param filterParams the map to store required filter params into
     * @param parentPassThroughWiths all the nodes, required to be passsed throug via WITH
     * @param additionalFilter additional filters to be applied to the where
     */
    private fun parseFilter(
            parsedQuery: ParsedQuery,
            useDistinct: Boolean,
            current: Node,
            variablePrefix: String,
            readingWithoutWhere: StatementBuilder.OngoingReadingWithoutWhere,
            type: GraphQLFieldsContainer,
            value: Value<Value<*>>?,
            filterParams: MutableMap<String, Any?>,
            parentPassThroughWiths: Collection<SymbolicName>,
            additionalFilter: ((StatementBuilder.ExposesWith) -> StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere)?
    ): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
        if (value is ObjectValue) {

            val readingWithWhere = addConditions<StatementBuilder.OngoingReadingWithWhere>(current, variablePrefix, parsedQuery.fieldPredicates, filterParams) { where, condition ->
                where?.and(condition) ?: readingWithoutWhere.where(condition)
            }
            var query = if (additionalFilter != null) {
                additionalFilter(readingWithWhere ?: readingWithoutWhere)
            } else {
                val withs = if (parsedQuery.relationPredicates.isNotEmpty() && parentPassThroughWiths.firstOrNull { it == current.requiredSymbolicName } == null) {
                    parentPassThroughWiths + current.requiredSymbolicName
                } else {
                    parentPassThroughWiths
                }
                withClauseWithOptionalDistinct(readingWithWhere ?: readingWithoutWhere, withs, useDistinct)
            }

            val levelPassThroughWiths = parentPassThroughWiths.toCollection(LinkedHashSet())
            for ((index, relFilter) in parsedQuery.relationPredicates.withIndex()) {
                val (op, objectField, relField) = relFilter
                if (objectField.value !is ObjectValue) {
                    if (objectField.value is NullValue) {
                        query = handleExists(relFilter, type, current, query, levelPassThroughWiths)
                        continue
                    } else {
                        throw OptimizedQueryException()
                    }
                }

                val nestedParsedQuery = parseQuery(objectField.value as ObjectValue, relField.type.getInnerFieldsContainer())
                if (nestedParsedQuery.fieldPredicates.isEmpty() && nestedParsedQuery.relationPredicates.isEmpty()) {
                    continue
                }
                val rel = type.relationshipFor(relField.name)!!
                val label = (relField.type.inner() as? GraphQLObjectType)?.label(quote = false)!!
                val relVariableName = variablePrefix + "_" + objectField.name
                val relNode = Cypher.node(label)
                val relVariable = relNode.named(relVariableName)


                if (index + 1 == parsedQuery.relationPredicates.size) {
                    levelPassThroughWiths.retainAll(levelPassThroughWiths)
                } else {
                    levelPassThroughWiths.add(current.requiredSymbolicName)
                }

                // convenience lambda to nest parsing
                val parseFilter = { additionalFilter: ((StatementBuilder.ExposesWith) -> StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere)? ->
                    parseFilter(nestedParsedQuery, true,
                            relVariable,
                            relVariableName,
                            createRelation(rel, query, current, relVariable),
                            rel.type,
                            objectField.value,
                            filterParams,
                            levelPassThroughWiths,
                            additionalFilter)
                }

                // used as additional filter to handle SINGLE and EVERY use cases
                val totalAndCountFilter = { whereClause: (withWithoutWhere: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere, totalVar: SymbolicName, countVar: SymbolicName) -> StatementBuilder.OrderableOngoingReadingAndWithWithWhere ->
                    { exposesWith: StatementBuilder.ExposesWith ->
                        val totalRel = createRelation(rel, current, relNode)
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

                @Suppress("DEPRECATION")
                when (op) {
                    RelationOperator.SOME -> query = parseFilter(null)
                    RelationOperator.EVERY -> query = parseFilter(totalAndCountFilter({ withWithoutWhere, total, count ->
                        withWithoutWhere.where(total.isEqualTo(count))
                    }))
                    RelationOperator.SINGLE -> query = parseFilter(totalAndCountFilter({ withWithoutWhere, total, count ->
                        withWithoutWhere
                            .where(total.isEqualTo(count))
                            .and(total.isEqualTo(Cypher.literalOf(1)))
                    }))
                    RelationOperator.NONE -> {
                        val patternWithoutWhere = Cypher.listBasedOn(createRelation(rel, current, relVariable))
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
                    else -> throw OptimizedQueryException()
                }
            }
            return query
        } else {
            throw OptimizedQueryException()
        }
    }

    private fun handleExists(
            relFilter: Predicate<RelationOperator>,
            type: GraphQLFieldsContainer,
            current: Node,
            query: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere,
            levelPassThroughWiths: LinkedHashSet<SymbolicName>
    ): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
        val (op, _, relField) = relFilter
        val rel = type.relationshipFor(relField.name)!!
        val label = (relField.type.inner() as? GraphQLObjectType)?.label(quote = false)!!
        val relNode = Cypher.node(label)
        val relation = createRelation(rel, current, relNode)
        val where = when (op) {
            RelationOperator.NOT -> query.where(relation)
            RelationOperator.EQ_OR_NOT_EXISTS -> query.where(Conditions.not(relation))
            else -> throw OptimizedQueryException()
        }
        return withClauseWithOptionalDistinct(where, levelPassThroughWiths)
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
            node: Node,
            variablePrefix: String,
            conditions: List<Predicate<FieldOperator>>,
            filterParams: MutableMap<String, Any?>,
            conditionAdder: (where: WithWhere?, condition: Condition) -> WithWhere
    ): WithWhere? {
        var where: WithWhere? = null
        for (conditionField in conditions) {
            val (predicate, objectField, field) = conditionField
            val prop = node.property(field.name)
            val parameter = Cypher.parameter(variablePrefix + "_" + objectField.name)
            val condition = predicate.conditionCreator(prop, parameter)
            filterParams[parameter.name] = objectField.value.toJavaValue()
            where = conditionAdder(where, condition)
        }
        return where
    }


    private fun createRelation(
            rel: RelationshipInfo,
            query: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere,
            start: Node,
            end: Node
    ): StatementBuilder.OngoingReadingWithoutWhere = query.match(createRelation(rel, start, end))


    private fun createRelation(rel: RelationshipInfo, start: Node, end: Node): Relationship =
            when (rel.out) {
                false -> start.relationshipFrom(end, rel.relType)
                true -> start.relationshipTo(end, rel.relType)
                null -> start.relationshipBetween(end, rel.relType)
            }


    private fun parseQuery(objectValue: ObjectValue, type: GraphQLFieldsContainer): ParsedQuery {
        val fieldPredicates = mutableListOf<Predicate<FieldOperator>>()
        val relationPredicates = mutableListOf<Predicate<RelationOperator>>()
        val queriedFields = objectValue.objectFields
            .mapIndexed { index, field -> field.name to (index to field) }
            .toMap(mutableMapOf())

        val or: List<Value<*>>? = queriedFields.remove("OR")?.second?.value?.let {
            (it as ArrayValue).values ?: throw IllegalArgumentException("OR is expected to be a list")
        }
        val and: List<Value<*>>? = queriedFields.remove("AND")?.second?.value?.let {
            (it as ArrayValue).values ?: throw IllegalArgumentException("AND is expected to be a list")
        }

        for (definedField in type.fieldDefinitions) {
            if (definedField.isRelationship()) {
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
            } else {
                FieldOperator.values()
                    .map { it to definedField.name + it.suffix }
                    .mapNotNull { (predicate, queryFieldName) ->
                        val (index, objectField) = queriedFields.remove(queryFieldName) ?: return@mapNotNull null
                        Predicate(predicate, objectField, definedField, index)
                    }
                    .forEach { fieldPredicates.add(it) }
            }
        }
        if (queriedFields.isNotEmpty()) {
            throw OptimizedQueryException("queried unknown fields: " + queriedFields.keys)
        }
        return ParsedQuery(
                fieldPredicates.sortedBy(Predicate<FieldOperator>::index),
                relationPredicates.sortedBy(Predicate<RelationOperator>::index),
                or,
                and)
    }

    data class ParsedQuery(
            val fieldPredicates: List<Predicate<FieldOperator>>,
            val relationPredicates: List<Predicate<RelationOperator>>,
            val or: List<Value<*>>?,
            val and: List<Value<*>>?
    )

    data class Predicate<T>(
            val op: T,
            val queryField: ObjectField,
            val fieldDefinition: GraphQLFieldDefinition,
            val index: Int)

}