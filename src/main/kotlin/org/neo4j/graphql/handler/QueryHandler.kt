package org.neo4j.graphql.handler

import graphql.Scalars
import graphql.language.*
import graphql.schema.*
import org.neo4j.graphql.*
import org.neo4j.graphql.Cypher
import org.neo4j.opencypherdsl.*
import org.neo4j.opencypherdsl.Cypher.name
import org.neo4j.opencypherdsl.Node
import org.neo4j.opencypherdsl.renderer.Renderer

class QueryHandler private constructor(
        type: GraphQLFieldsContainer,
        fieldDefinition: GraphQLFieldDefinition)
    : BaseDataFetcher(type, fieldDefinition) {

    class Factory(schemaConfig: SchemaConfig) : AugmentationHandler(schemaConfig) {
        override fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv) {
            if (!canHandle(type)) {
                return
            }
            val typeName = type.name
            val relevantFields = getRelevantFields(type)

            // TODO not just generate the input type but use it as well
            buildingEnv.addInputType("_${typeName}Input", type.relevantFields())
            val filterTypeName = buildingEnv.addFilterType(type)
            val orderingTypeName = buildingEnv.addOrdering(type)
            val builder = GraphQLFieldDefinition
                .newFieldDefinition()
                .name(typeName.decapitalize())
                .arguments(buildingEnv.getInputValueDefinitions(relevantFields) { true })
                .argument(input(FILTER, GraphQLTypeReference(filterTypeName)))
                .argument(input(FIRST, Scalars.GraphQLInt))
                .argument(input(OFFSET, Scalars.GraphQLInt))
                .type(GraphQLNonNull(GraphQLList(GraphQLNonNull(GraphQLTypeReference(type.name)))))
            if (orderingTypeName != null) {
                builder.argument(input(ORDER_BY, GraphQLTypeReference(orderingTypeName)))
            }
            val def = builder.build()
            buildingEnv.addOperation(QUERY, def)
        }

        override fun createDataFetcher(rootType: GraphQLObjectType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            if (rootType.name != QUERY) {
                return null
            }
            val cypherDirective = fieldDefinition.cypherDirective()
            if (cypherDirective != null) {
                return null
            }
            val type = fieldDefinition.type.inner() as? GraphQLFieldsContainer
                    ?: return null
            if (!canHandle(type)) {
                return null
            }
            return QueryHandler(type, fieldDefinition)
        }

        private fun canHandle(type: GraphQLFieldsContainer): Boolean {
            val typeName = type.innerName()
            if (!schemaConfig.query.enabled || schemaConfig.query.exclude.contains(typeName)) {
                return false
            }
            if (getRelevantFields(type).isEmpty()) {
                return false
            }
            return true
        }

        private fun getRelevantFields(type: GraphQLFieldsContainer): List<GraphQLFieldDefinition> {
            return type
                .relevantFields()
                .filter { it.dynamicPrefix() == null } // TODO currently we do not support filtering on dynamic properties
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Cypher {

        val mapProjection = projectFields(variable, field, type, env, null)
        val ordering = orderBy(variable, field.arguments)
        val skipLimit = SkipLimit(variable, field.arguments).format()

        val select = if (type.isRelationType()) {
            "()-[$variable:${label()}]->()"
        } else {
            "($variable:${label()})"
        }
        if ((env.getContext() as? QueryContext)?.optimizedQuery == true) {
            val rootNode = org.neo4j.opencypherdsl.Cypher.node(label()).named(variable)
            val c = org.neo4j.opencypherdsl.Cypher
                .match(rootNode)
            var c2: StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere? = null
            val filterParams = mutableMapOf<String, Any?>()
            for (argument in field.arguments) {
                if (argument.name == FILTER) {
                    val queryInfo = parseQueryValue(argument.value as ObjectValue, type)
                    c2 = parseFilter(
                            queryInfo,
                            rootNode,
                            false,
                            variable,
                            c, type, argument.value, filterParams, linkedSetOf(rootNode.requiredSymbolicName),
                            null
                    )
                } else {
                    throw OptimizedQueryException()
                }
            }
            val statement = c2
                ?.returningDistinct(PassThrough("${mapProjection.query} AS $variable$ordering${skipLimit.query}"))
                ?.build()
                    ?: throw OptimizedQueryException()
            val q = Renderer.getDefaultRenderer().render(statement)
            return Cypher(q, filterParams + mapProjection.params + skipLimit.params)
        }
        val where = where(variable, fieldDefinition, type, propertyArguments(field), field)
        return Cypher(
                """MATCH $select${where.query}
                  |RETURN ${mapProjection.query} AS $variable$ordering${skipLimit.query}""".trimMargin(),
                (where.params + mapProjection.params + skipLimit.params))
    }


    /**
     * 1. MATCH (parent)-[]-(current)
     * 2. WHERE MATCH all predicates for current
     * 3. WITH x
     * 4. Handle all quantifier (ALL / NONE / SINGLE / ANY)
     * 5. Handle OR / AND
     */
    private fun parseFilter(
            queryInfo: Info,
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

            val c = addConditions(current, variable, queryInfo.conditions, cypher, filterParams)
            var c2 = if (additionalFilter != null) {
                additionalFilter(c ?: cypher)
            } else {
                val withs = if (queryInfo.quantifier.isNotEmpty() && parentPassThroughWiths.firstOrNull { it == current.requiredSymbolicName } == null) {
                    parentPassThroughWiths + current.requiredSymbolicName
                } else {
                    parentPassThroughWiths
                }
                withClauseWithOptionalDistinct(c ?: cypher, withs, useDistinct)
            }

            val levelPassThroughWiths = parentPassThroughWiths.toCollection(LinkedHashSet())
            for ((index, relFilter) in queryInfo.quantifier.withIndex()) {
                val (op, objectField, relField) = relFilter
                if (objectField.value !is ObjectValue) {
                    // TODO
                    throw OptimizedQueryException()
                }

                val nestedQueryInfo = parseQueryValue(objectField.value as ObjectValue, relField.type.getInnerFieldsContainer())
                if (nestedQueryInfo.conditions.isEmpty() && nestedQueryInfo.quantifier.isEmpty()) {
                    continue
                }
                val rel = type.relationshipFor(relField.name)!!
                val label = (relField.type.inner() as? GraphQLObjectType)?.label()!!
                val relVariableName = variable + "_" + objectField.name
                val relNode = org.neo4j.opencypherdsl.Cypher.node(label)
                val relVariable = relNode.named(relVariableName)


                if (index + 1 == queryInfo.quantifier.size) {
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
                            if (nestedQueryInfo.quantifier.isNotEmpty()) {
                                additionalWiths = listOf(relVariable.requiredSymbolicName)
                            }

                            val where = withClauseWithOptionalDistinct(
                                    exposesWith,
                                    levelPassThroughWiths + additionalWiths + listOf(total, count)
                            )
                                .where(name(totalVar).isEqualTo(name(countVar)))
                            withClauseWithOptionalDistinct(where, levelPassThroughWiths + additionalWiths)
                        }
                    }
                    RelationOperator.NONE -> throw OptimizedQueryException()
                    RelationOperator.NOT -> throw OptimizedQueryException()
                    RelationOperator.EQ -> throw OptimizedQueryException()
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
            conditions: List<ConditionField>,
            cypher: StatementBuilder.OngoingReadingWithoutWhere,
            filterParams: MutableMap<String, Any?>
    ): StatementBuilder.OngoingReadingWithWhere? {
        var c: StatementBuilder.OngoingReadingWithWhere? = null
        for (conditionField in conditions) {
            val (predicate, objectField, field) = conditionField
            val prop = node.property(field.name)
            val parameter = org.neo4j.opencypherdsl.Cypher.parameter(variablePrefix + "_" + objectField.name)
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

    private fun parseQueryValue(objectValue: ObjectValue, type: GraphQLFieldsContainer): Info {
        val conditions = mutableListOf<ConditionField>()
        val quantifier = mutableListOf<RelFilter>()
        val queriedFields = objectValue.objectFields.mapIndexed { index, field -> field.name to (index to field) }.toMap(mutableMapOf())
        val or: List<Value<*>>? = queriedFields.remove("OR")?.second?.value?.let {
            (it as ArrayValue).values ?: throw IllegalAccessException("OR is expected to be a list")
        }
        val and: List<Value<*>>? = queriedFields.remove("AND")?.second?.value?.let {
            (it as ArrayValue).values ?: throw IllegalAccessException("AND is expected to be a list")
        }
        for (definedField in type.fieldDefinitions) {
            if (definedField.isRelationship()) {
                RelationOperator.values()
                    .map { it to definedField.name + it.suffix }
                    .mapNotNull { (queryOp, queryFieldName) ->
                        val (index, objectField) = queriedFields.remove(queryFieldName) ?: return@mapNotNull null
                        val op = if (queryOp == RelationOperator.EQ) {
                            if (definedField.isList()) RelationOperator.EVERY else RelationOperator.SOME
                        } else {
                            queryOp
                        }
                        RelFilter(op, objectField, definedField, index)
                    }
                    .forEach { quantifier.add(it) }
            } else {
                FieldOperator.values()
                    .map { it to definedField.name + it.suffix }
                    .mapNotNull { (predicate, queryFieldName) ->
                        val (index, objectField) = queriedFields.remove(queryFieldName) ?: return@mapNotNull null
                        ConditionField(predicate, objectField, definedField, index)
                    }
                    .forEach { conditions.add(it) }
            }
        }
        if (queriedFields.isNotEmpty()) {
            throw OptimizedQueryException("queried unknown fields: " + queriedFields.keys)
        }
        return Info(conditions.sortedBy(ConditionField::index), quantifier.sortedBy(RelFilter::index), or, and)
    }

    data class Info(
            val conditions: List<ConditionField>,
            val quantifier: List<RelFilter>,
            val or: List<Value<*>>?,
            val and: List<Value<*>>?
    )

    data class RelFilter(
            val op: RelationOperator,
            val queryField: ObjectField,
            val fieldDefinition: GraphQLFieldDefinition,
            val index: Int
    )

    data class ConditionField(
            val predicate: FieldOperator,
            val queryField: ObjectField,
            val fieldDefinition: GraphQLFieldDefinition,
            val index: Int
    )
}