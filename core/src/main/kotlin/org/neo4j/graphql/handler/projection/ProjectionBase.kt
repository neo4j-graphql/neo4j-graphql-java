package org.neo4j.graphql.handler.projection

import graphql.language.*
import graphql.schema.*
import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.Cypher.*
import org.neo4j.cypherdsl.core.Functions.head
import org.neo4j.cypherdsl.core.Functions.labels
import org.neo4j.cypherdsl.core.Node
import org.neo4j.cypherdsl.core.StatementBuilder.TerminalExposesLimit
import org.neo4j.cypherdsl.core.StatementBuilder.TerminalExposesSkip
import org.neo4j.graphql.*
import org.neo4j.graphql.parser.ParsedQuery
import org.neo4j.graphql.parser.QueryParser.parseArguments
import org.neo4j.graphql.parser.QueryParser.parseFilter

/**
 * This class contains the logic for projecting nodes and relations
 */
open class ProjectionBase(
        protected val schemaConfig: SchemaConfig
) {

    companion object {
        /*
         * old arguments, subject to be removed in future releases
         */

        const val NATIVE_ID = "_id"
        const val ORDER_BY = "orderBy"
        const val FIRST = "first"
        const val OFFSET = "offset"
        const val FILTER = "filter"

        /*
         * new arguments compatible with @neo4j/graphql
         */

        const val OPTIONS = "options"
        const val LIMIT = "limit"
        const val SKIP = "skip"
        const val SORT = "sort"
        const val WHERE = "where"

        const val TYPE_NAME = "__typename"

        /**
         * Fields with special treatments
         */
        val SPECIAL_FIELDS = setOf(FIRST, OFFSET, ORDER_BY, FILTER, OPTIONS)
    }

    fun filterFieldName() = if (schemaConfig.useWhereFilter) WHERE else FILTER

    fun orderBy(node: PropertyContainer, args: MutableList<Argument>, fieldDefinition: GraphQLFieldDefinition?, variables: Map<String, Any>): List<SortItem>? {
        val values = getOrderByArgs(args, fieldDefinition, variables)
        if (values.isEmpty()) {
            return null
        }
        return values
            .map { (property, direction) ->
                sort(node.property(property))
                    .let { if (direction == Sort.ASC) it.ascending() else it.descending() }
            }
    }

    private fun getOrderByArgs(args: MutableList<Argument>, fieldDefinition: GraphQLFieldDefinition?, variables: Map<String, Any>): List<Pair<String, Sort>> {
        val options = args.find { it.name == OPTIONS }?.value as? ObjectValue
        val defaultOptions = (fieldDefinition?.getArgument(OPTIONS)?.type as? GraphQLInputObjectType)
        return if (options != null || defaultOptions != null) {
            val sortArray = (options?.objectFields?.find { it.name == SORT }?.value
                ?.let { value -> (value as? VariableReference)?.let { variables[it.name] } ?: value }?.toJavaValue()
                    ?: defaultOptions?.getField(SORT)?.defaultValue?.toJavaValue()
                    ) as? List<*> ?: return emptyList()
            sortArray
                .mapNotNull { it as? Map<*, *> }
                .flatMap { it.entries }
                .filter { (key, sort) -> key is String && sort is String }
                .map { (key, sort) -> key as String to Sort.valueOf(sort as String) }
        } else {
            val orderBy = args.find { it.name == ORDER_BY }?.value
                    ?: fieldDefinition?.getArgument(ORDER_BY)?.defaultValue?.asGraphQLValue()
            orderBy
                ?.let { it ->
                    when (it) {
                        is ArrayValue -> it.values.map { it.toJavaValue().toString() }
                        is EnumValue -> listOf(it.name)
                        is StringValue -> listOf(it.value)
                        else -> null
                    }
                }
                ?.map {
                    val index = it.lastIndexOf('_')
                    val property = it.substring(0, index)
                    val direction = Sort.valueOf(it.substring(index + 1).toUpperCase())
                    property to direction
                } ?: emptyList()
        }
    }

    fun where(
            propertyContainer: PropertyContainer,
            fieldDefinition: GraphQLFieldDefinition,
            type: GraphQLFieldsContainer,
            field: Field,
            variables: Map<String, Any>
    ): Condition {
        val variable = propertyContainer.requiredSymbolicName.value

        val result = if (!schemaConfig.useWhereFilter) {
            val filteredArguments = field.arguments.filterNot { SPECIAL_FIELDS.contains(it.name) }

            val parsedQuery = parseArguments(filteredArguments, fieldDefinition, type, variables)
            handleQuery(variable, "", propertyContainer, parsedQuery, type, variables)
        } else {
            Conditions.noCondition()
        }
        return field.arguments.find { filterFieldName() == it.name }
            ?.let { arg ->
                when (arg.value) {
                    is ObjectValue -> arg.value as ObjectValue
                    is VariableReference -> variables[arg.name]?.let { it.asGraphQLValue() }
                    else -> throw IllegalArgumentException("")
                }
            }
            ?.let { parseFilter(it as ObjectValue, type, variables) }
            ?.let {
                val filterCondition = handleQuery(normalizeName(filterFieldName(), variable), "", propertyContainer, it, type, variables)
                result.and(filterCondition)
            }
                ?: result
    }

    protected fun handleQuery(
            variablePrefix: String,
            variableSuffix: String,
            propertyContainer: PropertyContainer,
            parsedQuery: ParsedQuery,
            type: GraphQLFieldsContainer,
            variables: Map<String, Any>
    ): Condition {
        var result = parsedQuery.getFieldConditions(propertyContainer, variablePrefix, variableSuffix, schemaConfig)

        for (predicate in parsedQuery.relationPredicates) {
            val objectField = predicate.queryField

            if (objectField.value is NullValue) {
                val existsCondition = predicate.createExistsCondition(propertyContainer)
                result = result.and(existsCondition)
                continue
            }
            if (objectField.value !is ObjectValue) {
                throw IllegalArgumentException("Only object values are supported for filtering on queried relation ${predicate.queryField}, but got ${objectField.value.javaClass.name}")
            }

            val cond = name(normalizeName(variablePrefix, predicate.relationshipInfo.typeName, "Cond"))
            when (predicate.op) {
                RelationOperator.SOME -> Predicates.any(cond)
                RelationOperator.SINGLE -> Predicates.single(cond)
                RelationOperator.EVERY -> Predicates.all(cond)
                RelationOperator.NOT -> Predicates.all(cond)
                RelationOperator.NONE -> Predicates.none(cond)
                else -> null
            }?.let {
                val targetNode = predicate.relNode.named(normalizeName(variablePrefix, predicate.relationshipInfo.typeName))
                val relType = predicate.relationshipInfo.type
                val parsedQuery2 = parseFilter(objectField.value as ObjectValue, relType, variables)
                val condition = handleQuery(targetNode.requiredSymbolicName.value, "", targetNode, parsedQuery2, relType, variables)
                var where = it
                    .`in`(listBasedOn(predicate.relationshipInfo.createRelation(propertyContainer as Node, targetNode)).returning(condition))
                    .where(cond.asCondition())
                if (predicate.op == RelationOperator.NOT) {
                    where = where.not()
                }
                result = result.and(where)

            }
        }

        fun handleLogicalOperator(value: Value<*>, classifier: String, variables: Map<String, Any>): Condition {
            val objectValue = value as? ObjectValue
                    ?: throw IllegalArgumentException("Only object values are supported for logical operations, but got ${value.javaClass.name}")

            val parsedNestedQuery = parseFilter(objectValue, type, variables)
            return handleQuery(variablePrefix + classifier, variableSuffix, propertyContainer, parsedNestedQuery, type, variables)
        }

        fun handleLogicalOperators(values: List<Value<*>>?, classifier: String): List<Condition> {
            return when {
                values?.isNotEmpty() == true -> when {
                    values.size > 1 -> values.mapIndexed { index, value -> handleLogicalOperator(value, "${classifier}${index + 1}", variables) }
                    else -> values.map { value -> handleLogicalOperator(value, "", variables) }
                }
                else -> emptyList()
            }
        }
        handleLogicalOperators(parsedQuery.and, "And").forEach { result = result.and(it) }
        handleLogicalOperators(parsedQuery.or, "Or").forEach { result = result.or(it) }

        return result
    }

    fun projectFields(propertyContainer: PropertyContainer, field: Field, nodeType: GraphQLFieldsContainer, env: DataFetchingEnvironment, variableSuffix: String? = null, propertiesToSkipDeepProjection: Set<String> = emptySet()): List<Any> {
        return projectFields(propertyContainer, propertyContainer.requiredSymbolicName, field, nodeType, env, variableSuffix, propertiesToSkipDeepProjection)
    }

    fun projectFields(propertyContainer: PropertyContainer, variable: SymbolicName, field: Field, nodeType: GraphQLFieldsContainer, env: DataFetchingEnvironment, variableSuffix: String? = null, propertiesToSkipDeepProjection: Set<String> = emptySet()): List<Any> {
        return projectSelection(propertyContainer, variable, field.selectionSet.selections, nodeType, env, variableSuffix, propertiesToSkipDeepProjection)
    }

    private fun projectSelection(propertyContainer: PropertyContainer, variable: SymbolicName, selection: List<Selection<*>>, nodeType: GraphQLFieldsContainer, env: DataFetchingEnvironment, variableSuffix: String?, propertiesToSkipDeepProjection: Set<String> = emptySet()): List<Any> {
        // TODO just render fragments on valid types (Labels) by using cypher like this:
        // apoc.map.mergeList([
        //  a{.name},
        //  CASE WHEN a:Location THEN a { .foo } ELSE {} END
        //  ])
        var hasTypeName = false
        var projections = selection.flatMap {
            when (it) {
                is Field -> {
                    hasTypeName = hasTypeName || (it.name == TYPE_NAME)
                    projectField(propertyContainer, variable, it, nodeType, env, variableSuffix, propertiesToSkipDeepProjection)
                }
                is InlineFragment -> projectInlineFragment(propertyContainer, variable, it, env, variableSuffix)
                is FragmentSpread -> projectNamedFragments(propertyContainer, variable, it, env, variableSuffix)
                else -> emptyList()
            }
        }
        if (nodeType is GraphQLInterfaceType
            && !hasTypeName
            && (env.getLocalContext() as? QueryContext)?.queryTypeOfInterfaces == true
        ) {
            // for interfaces the typename is required to determine the correct implementation
            projections = projections + projectField(propertyContainer, variable, Field(TYPE_NAME), nodeType, env, variableSuffix)
        }
        return projections
    }

    private fun projectField(propertyContainer: PropertyContainer, variable: SymbolicName, field: Field, type: GraphQLFieldsContainer, env: DataFetchingEnvironment, variableSuffix: String?, propertiesToSkipDeepProjection: Set<String> = emptySet()): List<Any> {
        val projections = mutableListOf<Any>()

        if (field.name == TYPE_NAME) {
            projections += field.aliasOrName()
            if (type.isRelationType()) {
                projections += literalOf<Any>(type.name)
            } else {
                val label = name("label")
                val parameter = queryParameter(type.getValidTypeLabels(env.graphQLSchema), variable.value, "validTypes")
                projections += head(listWith(label).`in`(labels(propertyContainer as? Node
                        ?: throw IllegalStateException("Labels are only supported for nodes"))).where(label.`in`(parameter)).returning())
            }
            return projections
        }

        val fieldDefinition = type.getFieldDefinition(field.name)
                ?: throw IllegalStateException("No field ${field.name} in ${type.name}")
        if (fieldDefinition.isIgnored()) {
            return projections
        }
        projections += field.aliasOrName()
        val cypherDirective = fieldDefinition.cypherDirective()
        val isObjectField = fieldDefinition.type.inner() is GraphQLFieldsContainer
        if (cypherDirective != null) {
            val query = cypherDirective(field.contextualize(variable), fieldDefinition, field, cypherDirective, propertyContainer.requiredSymbolicName)
            projections += if (isObjectField && !cypherDirective.passThrough) {
                projectListComprehension(variable, field, fieldDefinition, env, query, variableSuffix)
            } else {
                query
            }

        } else when {
            schemaConfig.useTemporalScalars && fieldDefinition.isNeo4jTemporalType() -> {
                projections += getNeo4jTypeConverter(fieldDefinition).projectField(variable, field, "")
            }
            isObjectField -> {
                if (fieldDefinition.isNeo4jType()) {
                    if (propertiesToSkipDeepProjection.contains(fieldDefinition.name)) {
                        // if the property has an internal type like Date or DateTime and we want to compute on this
                        // type (e.g sorting), we need to pass out the whole property and do the concrete projection
                        // after the outer computation is done
                        projections += propertyContainer.property(fieldDefinition.propertyName())
                    } else {
                        projections += projectNeo4jObjectType(variable, field, fieldDefinition)
                    }
                } else {
                    projections += projectRelationship(propertyContainer, variable, field, fieldDefinition, type, env, variableSuffix)
                }
            }
            fieldDefinition.isNativeId() -> {
                projections += Functions.id(anyNode(variable))
            }
            else -> {
                val dynamicPrefix = fieldDefinition.dynamicPrefix()
                when {
                    dynamicPrefix != null -> {
                        val key = name("key")
                        projections += call("apoc.map.fromPairs").withArgs(
                                listWith(key)
                                    .`in`(call("keys").withArgs(variable).asFunction())
                                    .where(key.startsWith(literalOf<String>(dynamicPrefix)))
                                    .returning(
                                            call("substring").withArgs(key, literalOf<Int>(dynamicPrefix.length)).asFunction(),
                                            property(variable, key)
                                    )
                        )
                            .asFunction()
                    }
                    field.aliasOrName() != fieldDefinition.propertyName() -> {
                        projections += variable.property(fieldDefinition.propertyName())
                    }
                }
            }
        }
        return projections
    }

    private fun projectNeo4jObjectType(variable: SymbolicName, field: Field, fieldDefinition: GraphQLFieldDefinition): Expression {
        val converter = getNeo4jTypeConverter(fieldDefinition)
        val projections = mutableListOf<Any>()
        field.selectionSet.selections
            .filterIsInstance<Field>()
            .forEach {
                projections += it.name
                projections += converter.projectField(variable, field, it.name)
            }
        return mapOf(*projections.toTypedArray())
    }

    fun cypherDirective(variable: String, fieldDefinition: GraphQLFieldDefinition, field: Field, cypherDirective: CypherDirective, thisValue: Any? = null): Expression {
        val suffix = if (fieldDefinition.type.isList()) "Many" else "Single"
        val args = cypherDirectiveQuery(variable, fieldDefinition, field, cypherDirective, thisValue)
        return call("apoc.cypher.runFirstColumn$suffix").withArgs(*args).asFunction()
    }

    fun cypherDirectiveQuery(variable: String, fieldDefinition: GraphQLFieldDefinition, field: Field, cypherDirective: CypherDirective, thisValue: Any? = null): Array<Expression> {
        val args = mutableMapOf<String, Any?>()
        if (thisValue != null) args["this"] = thisValue
        field.arguments.forEach { args[it.name] = it.value }
        fieldDefinition.arguments
            .filter { it.defaultValue != null && !args.containsKey(it.name) }
            .forEach { args[it.name] = it.defaultValue }

        val argParams = args.map { (name, _) -> "$$name AS $name" }.joinNonEmpty(", ")
        val query = (if (argParams.isEmpty()) "" else "WITH $argParams ") + cypherDirective.statement
        val argExpressions = args.flatMap { (name, value) -> listOf(name, if (name == "this") value else queryParameter(value, variable, name)) }
        return arrayOf(literalOf<String>(query), mapOf(*argExpressions.toTypedArray()))
    }

    private fun projectNamedFragments(node: PropertyContainer, variable: SymbolicName, fragmentSpread: FragmentSpread, env: DataFetchingEnvironment, variableSuffix: String?) =
            env.fragmentsByName.getValue(fragmentSpread.name).let {
                projectFragment(node, it.typeCondition.name, variable, env, variableSuffix, it.selectionSet)
            }

    private fun projectInlineFragment(node: PropertyContainer, variable: SymbolicName, fragment: InlineFragment, env: DataFetchingEnvironment, variableSuffix: String?) =
            projectFragment(node, fragment.typeCondition.name, variable, env, variableSuffix, fragment.selectionSet)

    private fun projectFragment(node: PropertyContainer, fragmentTypeName: String?, variable: SymbolicName, env: DataFetchingEnvironment, variableSuffix: String?, selectionSet: SelectionSet): List<Any> {
        val fragmentType = env.graphQLSchema.getType(fragmentTypeName) as? GraphQLFieldsContainer ?: return emptyList()
        // these are the nested fields of the fragment
        // it could be that we have to adapt the variable name too, and perhaps add some kind of rename
        return projectSelection(node, variable, selectionSet.selections, fragmentType, env, variableSuffix)
    }


    private fun projectRelationship(node: PropertyContainer, variable: SymbolicName, field: Field, fieldDefinition: GraphQLFieldDefinition, parent: GraphQLFieldsContainer, env: DataFetchingEnvironment, variableSuffix: String?): Expression {
        return when (parent.isRelationType()) {
            true -> projectRelationshipParent(node, variable, field, fieldDefinition, parent, env, variableSuffix)
            else -> projectRichAndRegularRelationship(variable, field, fieldDefinition, parent, env)
        }
    }

    private fun projectListComprehension(variable: SymbolicName, field: Field, fieldDefinition: GraphQLFieldDefinition, env: DataFetchingEnvironment, expression: Expression, variableSuffix: String?): Expression {
        val fieldObjectType = fieldDefinition.type.getInnerFieldsContainer()
        val fieldType = fieldDefinition.type
        val childVariableName = field.contextualize(variable)
        val childVariable = name(childVariableName)

        val fieldProjection = projectFields(anyNode(childVariable), childVariable, field, fieldObjectType, env, variableSuffix)

        val comprehension = listWith(childVariable).`in`(expression).returning(childVariable.project(fieldProjection))
        val skipLimit = SkipLimit(childVariableName, field.arguments, fieldDefinition)
        return skipLimit.slice(fieldType.isList(), comprehension)
    }

    private fun relationshipInfoInCorrectDirection(
            fieldObjectType: GraphQLFieldsContainer,
            relInfo0: RelationshipInfo<GraphQLFieldsContainer>,
            parent: GraphQLFieldsContainer,
            relDirectiveField: RelationshipInfo<GraphQLFieldsContainer>?
    ): RelationshipInfo<GraphQLFieldsContainer> {
        val startField = fieldObjectType.getRelevantFieldDefinition(relInfo0.startField)!!
        val endField = fieldObjectType.getRelevantFieldDefinition(relInfo0.endField)!!
        val startFieldTypeName = startField.type.innerName()
        val inverse = startFieldTypeName != parent.name
                || startFieldTypeName == endField.type.innerName()
                && relDirectiveField?.direction != relInfo0.direction
        return if (inverse) relInfo0.copy(direction = relInfo0.direction.invert(), startField = relInfo0.endField, endField = relInfo0.startField) else relInfo0
    }

    private fun projectRelationshipParent(propertyContainer: PropertyContainer, variable: SymbolicName, field: Field, fieldDefinition: GraphQLFieldDefinition, parent: GraphQLFieldsContainer, env: DataFetchingEnvironment, variableSuffix: String?): Expression {
        val fieldObjectType = fieldDefinition.type.inner() as? GraphQLFieldsContainer
                ?: throw IllegalArgumentException("field ${fieldDefinition.name} of type ${parent.name} is not an object (fields container) and can not be handled as relationship")
        return when (propertyContainer) {
            is Node -> {
                val projectionEntries = projectFields(propertyContainer, name(variable.value + (variableSuffix?.capitalize()
                        ?: "")), field, fieldObjectType, env, variableSuffix)
                propertyContainer.project(projectionEntries)
            }
            is Relationship -> projectNodeFromRichRelationship(parent, fieldDefinition, variable, field, env)
            else -> throw IllegalArgumentException("${propertyContainer.javaClass.name} cannot be handled for field ${fieldDefinition.name} of type ${parent.name}")
        }
    }

    private fun projectNodeFromRichRelationship(
            parent: GraphQLFieldsContainer,
            fieldDefinition: GraphQLFieldDefinition,
            variable: SymbolicName,
            field: Field,
            env: DataFetchingEnvironment
    ): Expression {
        val relInfo = parent.relationship()
                ?: throw IllegalStateException(parent.name + " is not an relation type")

        val node = CypherDSL.node(fieldDefinition.type.name()).named(fieldDefinition.name)
        val (start, end, target) = when (fieldDefinition.name) {
            relInfo.startField -> Triple(node, anyNode(), node)
            relInfo.endField -> Triple(anyNode(), node, node)
            else -> throw IllegalArgumentException("type ${parent.name} does not have a matching field with name ${fieldDefinition.name}")
        }
        val rel = relInfo.createRelation(start, end, false, variable)
        return head(CypherDSL.listBasedOn(rel).returning(target.project(projectFields(target, field, fieldDefinition.type as GraphQLFieldsContainer, env))))
    }

    private fun projectRichAndRegularRelationship(variable: SymbolicName, field: Field, fieldDefinition: GraphQLFieldDefinition, parent: GraphQLFieldsContainer, env: DataFetchingEnvironment): Expression {
        val fieldType = fieldDefinition.type
        val nodeType = fieldType.getInnerFieldsContainer()

        // todo combine both nestings if rel-entity
        val relDirectiveObject = (nodeType as? GraphQLDirectiveContainer)?.getDirective(DirectiveConstants.RELATION)?.let { RelationshipInfo.create(nodeType, it) }
        val relDirectiveField = fieldDefinition.getDirective(DirectiveConstants.RELATION)?.let { RelationshipInfo.create(nodeType, it) }

        val (relInfo0, isRelFromType) =
                relDirectiveObject?.let { it to true }
                        ?: relDirectiveField?.let { it to false }
                        ?: throw IllegalStateException("Field $field needs an @relation directive")

        val relInfo = if (isRelFromType) relationshipInfoInCorrectDirection(nodeType, relInfo0, parent, relDirectiveField) else relInfo0

        val childVariable = field.contextualize(variable)
        val childVariableName = name(childVariable)

        val (endNodePattern, variableSuffix) = when {
            isRelFromType -> {
                val label = nodeType.getRelevantFieldDefinition(relInfo.endField)!!.type.innerName()
                node(label).named("$childVariable${relInfo.endField.capitalize()}") to relInfo.endField
            }
            else -> node(nodeType.name).named(childVariableName) to null
        }

        val skipLimit = SkipLimit(childVariable, field.arguments, fieldDefinition)
        val orderBy = getOrderByArgs(field.arguments, fieldDefinition, env.variables)
        val sortByNeo4jTypeFields = orderBy
            .filter { (property, _) -> nodeType.getRelevantFieldDefinition(property)?.isNeo4jType() == true }
            .map { (property, _) -> property }
            .toSet()

        val projectionEntries = projectFields(endNodePattern, name(childVariable), field, nodeType, env, variableSuffix, sortByNeo4jTypeFields)


        var relationship = relInfo.createRelation(anyNode(variable), endNodePattern)
        if (isRelFromType) {
            relationship = relationship.named(childVariableName)
        }

        val where = where(anyNode(childVariableName), fieldDefinition, nodeType, field, env.variables)
        var comprehension: Expression = listBasedOn(relationship).where(where).returning(childVariableName.project(projectionEntries))
        if (orderBy.isNotEmpty()) {
            val sortArgs = orderBy.map { (property, direction) -> literalOf<String>(if (direction == Sort.ASC) "^$property" else property) }
            comprehension = call("apoc.coll.sortMulti")
                .withArgs(comprehension, listOf(*sortArgs.toTypedArray()))
                .asFunction()
            if (sortByNeo4jTypeFields.isNotEmpty()) {
                val neo4jFieldSelection = field.selectionSet.selections
                    .filter { selection -> sortByNeo4jTypeFields.contains((selection as? Field)?.name) }
                val deferredProjection = mutableListOf<Any>(Asterisk.INSTANCE)
                val sortedElement = name("sortedElement")
                deferredProjection.addAll(projectSelection(anyNode(sortedElement), sortedElement, neo4jFieldSelection, nodeType, env, variableSuffix))

                comprehension = listWith(sortedElement).`in`(comprehension)
                    .returning(sortedElement.project(deferredProjection))
            }
        }
        return skipLimit.slice(fieldType.isList(), comprehension)
    }

    inner class SkipLimit(variable: String, arguments: List<Argument>, fieldDefinition: GraphQLFieldDefinition?) {

        private val skip: Parameter<*>?
        private val limit: Parameter<*>?

        init {
            if (schemaConfig.queryOptionStyle == SchemaConfig.InputStyle.INPUT_TYPE) {
                val options = arguments.find { it.name == OPTIONS }?.value as? ObjectValue
                val defaultOptions = (fieldDefinition?.getArgument(OPTIONS)?.type as? GraphQLInputObjectType)
                this.skip = convertOptionField(variable, options, defaultOptions, SKIP)
                this.limit = convertOptionField(variable, options, defaultOptions, LIMIT)
            } else {
                this.skip = convertArgument(variable, arguments, fieldDefinition, OFFSET)
                this.limit = convertArgument(variable, arguments, fieldDefinition, FIRST)
            }
        }

        fun <T> format(returning: T): StatementBuilder.BuildableStatement where T : TerminalExposesSkip, T : TerminalExposesLimit {
            val result = skip?.let { returning.skip(it) } ?: returning
            return limit?.let { result.limit(it) } ?: result
        }

        fun slice(list: Boolean, expression: Expression): Expression =
                if (!list) {
                    skip?.let { valueAt(expression, it) } ?: valueAt(expression, 0)
                } else when (limit) {
                    null -> skip?.let { subListFrom(expression, it) } ?: expression
                    else -> skip?.let { subList(expression, it, it.add(limit)) }
                            ?: subList(expression, literalOf<Number>(0), limit)
                }

        private fun convertArgument(variable: String, arguments: List<Argument>, fieldDefinition: GraphQLFieldDefinition?, name: String): Parameter<*>? {
            val value = arguments
                .find { it.name.toLowerCase() == name }?.value
                    ?: fieldDefinition?.getArgument(name)?.defaultValue
                    ?: return null
            return queryParameter(value, variable, name)
        }

        private fun convertOptionField(variable: String, options: ObjectValue?, defaultOptions: GraphQLInputObjectType?, name: String): Parameter<*>? {
            val value = options?.objectFields?.find { it.name == name }?.value
                    ?: defaultOptions?.getField(name)?.defaultValue
                    ?: return null
            return queryParameter(value, variable, name)
        }
    }

    enum class Sort {
        ASC,
        DESC
    }
}
