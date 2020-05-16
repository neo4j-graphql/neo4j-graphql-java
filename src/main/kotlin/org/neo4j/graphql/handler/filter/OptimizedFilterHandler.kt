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
                withWithoutWhere = parseFilter(queryInfo, rootNode, false, variable, readingWithoutWhere,
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
     */
    private fun parseFilter(
            parsedQuery: ParsedQuery,
            current: Node,
            useDistinct: Boolean,
            variable: String,
            cypher: StatementBuilder.OngoingReadingWithoutWhere,
            type: GraphQLFieldsContainer,
            value: Value<Value<*>>?,
            filterParams: MutableMap<String, Any?>,
            parentPassThroughWiths: Collection<SymbolicName>,
            additionalFilter: ((StatementBuilder.ExposesWith) -> StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere)?
    ): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
        if (value is ObjectValue) {

            val c = addConditions(current, variable, parsedQuery.fieldPredicates, cypher, filterParams)
            var c2 = if (additionalFilter != null) {
                additionalFilter(c ?: cypher)
            } else {
                val withs = if (parsedQuery.relationPredicates.isNotEmpty() && parentPassThroughWiths.firstOrNull { it == current.requiredSymbolicName } == null) {
                    parentPassThroughWiths + current.requiredSymbolicName
                } else {
                    parentPassThroughWiths
                }
                withClauseWithOptionalDistinct(c ?: cypher, withs, useDistinct)
            }

            val levelPassThroughWiths = parentPassThroughWiths.toCollection(LinkedHashSet())
            for ((index, relFilter) in parsedQuery.relationPredicates.withIndex()) {
                val (op, objectField, relField) = relFilter
                if (objectField.value !is ObjectValue) {
                    // TODO
                    throw OptimizedQueryException()
                }

                val nestedQueryInfo = parseQuery(objectField.value as ObjectValue, relField.type.getInnerFieldsContainer())
                if (nestedQueryInfo.fieldPredicates.isEmpty() && nestedQueryInfo.relationPredicates.isEmpty()) {
                    continue
                }
                val rel = type.relationshipFor(relField.name)!!
                val label = (relField.type.inner() as? GraphQLObjectType)?.label(quote = false)!!
                val relVariableName = variable + "_" + objectField.name
                val relNode = Cypher.node(label)
                val relVariable = relNode.named(relVariableName)


                if (index + 1 == parsedQuery.relationPredicates.size) {
                    levelPassThroughWiths.retainAll(parentPassThroughWiths)
                } else {
                    levelPassThroughWiths.add(current.requiredSymbolicName)
                }

                @Suppress("DEPRECATION")
                when (op) {
                    RelationOperator.SINGLE, RelationOperator.SOME -> {
                        val c3 = createRelation(rel, c2, current, relVariable)
                        c2 = parseFilter(
                                nestedQueryInfo,
                                relVariable,
                                true,
                                relVariableName,
                                c3,
                                rel.type,
                                objectField.value,
                                filterParams,
                                levelPassThroughWiths,
                                null)
                    }
                    RelationOperator.EVERY -> {
                        val c3 = createRelation(rel, c2, current, relVariable)
                        c2 = parseFilter(
                                nestedQueryInfo,
                                relVariable,
                                true,
                                relVariableName,
                                c3,
                                rel.type,
                                objectField.value,
                                filterParams,
                                levelPassThroughWiths) { exposesWith ->

                            val totalRel = createRelation(rel, current, relNode)
                            val totalVar = relVariableName + "_total"
                            val total = Functions.size(totalRel).`as`(totalVar)
                            val countVar = relVariableName + "_count"
                            val count = Functions.countDistinct(relVariable).`as`(countVar)
                            var additionalWiths = emptyList<SymbolicName>()
                            if (nestedQueryInfo.relationPredicates.isNotEmpty()) {
                                additionalWiths = listOf(relVariable.requiredSymbolicName)
                            }

                            val where = withClauseWithOptionalDistinct(
                                    exposesWith,
                                    levelPassThroughWiths + additionalWiths + listOf(total, count)
                            )
                                .where(Cypher.name(totalVar).isEqualTo(Cypher.name(countVar)))
                            withClauseWithOptionalDistinct(where, levelPassThroughWiths + additionalWiths)
                        }
                    }
                    RelationOperator.NONE -> throw OptimizedQueryException()
                    RelationOperator.NOT -> throw OptimizedQueryException()
                    RelationOperator.EQ_OR_NOT_EXISTS -> throw OptimizedQueryException()
                }
            }
            return c2
        } else {
            // TODO
            throw OptimizedQueryException()
        }
    }

    private fun withClauseWithOptionalDistinct(
            exposesWith: StatementBuilder.ExposesWith,
            withs: Collection<Expression>,
            useDistinct: Boolean = true): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere =
            when {
                useDistinct && withs.size == 1 -> exposesWith.withDistinct(*withs.toTypedArray())
                else -> exposesWith.with(*withs.toTypedArray())
            }

    private fun addConditions(
            node: Node,
            variablePrefix: String,
            conditions: List<Predicate<FieldOperator>>,
            cypher: StatementBuilder.OngoingReadingWithoutWhere,
            filterParams: MutableMap<String, Any?>
    ): StatementBuilder.OngoingReadingWithWhere? {
        var c: StatementBuilder.OngoingReadingWithWhere? = null
        for (conditionField in conditions) {
            val (predicate, objectField, field) = conditionField
            val prop = node.property(field.name)
            val parameter = Cypher.parameter(variablePrefix + "_" + objectField.name)
            val condition = predicate.conditionCreator(prop, parameter)
            filterParams[parameter.name] = objectField.value.toJavaValue()
            c = c?.and(condition) ?: cypher.where(condition)
        }
        return c
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
                        val op = when (definedField.isList()){
                            true -> when(queryOp){
                                RelationOperator.EQ_OR_NOT_EXISTS -> {
                                    LOGGER.info("$queryFieldName on type ${type.name} was used for filtering, consider using ${definedField.name}${RelationOperator.EVERY.suffix} instead")
                                    RelationOperator.EVERY
                                }
                                else -> queryOp
                            }
                            false -> when (queryOp){
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
                                    RelationOperator.NOT
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