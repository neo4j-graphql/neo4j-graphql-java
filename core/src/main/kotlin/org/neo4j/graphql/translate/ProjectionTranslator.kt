package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.*
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.field_arguments.RelationFieldInputArgs
import org.neo4j.graphql.schema.model.inputs.options.OptionsInput
import org.neo4j.graphql.translate.connection_clause.CreateConnectionClause
import org.neo4j.graphql.translate.field_aggregation.CreateFieldAggregation
import org.neo4j.graphql.translate.projection.createInterfaceProjectionAndParams
import org.neo4j.graphql.translate.projection.projectCypherField
import org.neo4j.graphql.translate.projection.projectScalarField
import org.neo4j.graphql.translate.where.PrefixUsage
import org.neo4j.graphql.translate.where.createWhere
import org.neo4j.graphql.utils.ResolveTree
import org.neo4j.graphql.utils.ResolveTree.Companion.generateMissingOrAliasedFields

class ProjectionTranslator {

    fun createProjectionAndParams(
        node: Node,
        varName: org.neo4j.cypherdsl.core.Node,
        resolveTree: ResolveTree?,
        chainStr: ChainString? = null,
        schemaConfig: SchemaConfig,
        queryContext: QueryContext,
        withVars: List<SymbolicName> = emptyList(),
        resolveType: Boolean = false,
        useShortcut: Boolean = true,
        connectPrefixUsage: PrefixUsage = PrefixUsage.APPEND
    ): Projection {
        val projections = mutableListOf<Any>()

        if (resolveType) {
            projections += Constants.RESOLVE_TYPE
            projections += node.name.asCypherLiteral()
        }

        val selectedFields =
            resolveTree?.fieldsByTypeName?.get(node.name) ?: return Projection(projection = projections)

        val options = resolveTree.args.nestedDict(Constants.OPTIONS)?.let { OptionsInput.create(it) }

        val mergedFields = selectedFields + generateMissingOrAliasedRequiredFields(node, selectedFields)

        var authValidate: Condition? = null
        val subQueries = mutableListOf<Statement>()
        val subQueriesBeforeSort = mutableListOf<Statement>()
        val sortFields = mutableMapOf<String, Expression>()

        mergedFields.values.forEach { field ->
            val alias = field.aliasOrName
            val param = chainStr
//                ?.extend(alias)
                ?: ChainString(schemaConfig, varName, alias)

            val (isAggregate, nodeField) = node.relationAggregationFields[field.name]
                ?.let { true to it }
                ?: (false to node.getField(field.name))
            if (nodeField == null) {
                return@forEach
            }

            if (nodeField is AuthableField) {
                nodeField.auth?.let { auth ->
                    AuthTranslator(
                        schemaConfig,
                        queryContext,
                        allow = AuthTranslator.AuthOptions(varName, node)
                    )
                        .createAuth(auth, AuthDirective.AuthOperation.READ)
                        ?.let { authValidate = authValidate and it }
                }
            }

            if (nodeField is CypherField) {

                val resultReference = Cypher.name(param.resolveName())

                val customCypherStatement =
                    projectCypherField(field, nodeField, varName, resultReference, queryContext, schemaConfig)

                if (options?.sort?.find { it.containsKey(field.name) } != null) {
                    sortFields[field.name] = resultReference
                    subQueriesBeforeSort += customCypherStatement
                } else {
                    subQueries += customCypherStatement
                }
                projections += field.aliasOrName
                projections += resultReference
                return@forEach
            }

            if (nodeField is RelationField && !isAggregate) {
                val referenceNode = nodeField.node
                val isArray = nodeField.typeMeta.type.isList()
                val arguments = RelationFieldInputArgs(nodeField, field.args)

                if (nodeField.interfaze != null) {
                    val returnVariable = Cypher.name(ChainString(schemaConfig, varName, field).resolveName())

                    subQueries += createInterfaceProjectionAndParams(
                        field, nodeField, varName, withVars, returnVariable, queryContext, schemaConfig
                    )
                    projections += alias
                    projections += returnVariable

                } else if (nodeField.isUnion) {
                    val referenceNodes = requireNotNull(nodeField.union).nodes.values
                    projections += alias
                    val endNode = Cypher.anyNode(param.resolveName())
                    val rel = nodeField.createDslRelation(varName, endNode)
                    var unionConditions: Condition? = null
                    val p = endNode.requiredSymbolicName
                    val unionProjection = mutableListOf<Expression>()
                    referenceNodes.forEach { refNode ->
                        var labelCondition: Condition? = null
                        refNode.allLabels(queryContext).forEach {
                            labelCondition = labelCondition and it.asCypherLiteral().`in`(Functions.labels(endNode))
                        }
                        labelCondition?.let { unionConditions = unionConditions or it }

                        // TODO __resolveType vs __typename ?
                        val projection = mutableListOf(Constants.RESOLVE_TYPE, refNode.name.asCypherLiteral())
                        val typeFields = field.fieldsByTypeName[refNode.name]

                        var unionCondition = labelCondition

                        if (!typeFields.isNullOrEmpty()) {
                            val whereInput = arguments.where as WhereInput.UnionWhereInput?

                            val recurse = createProjectionAndParams(
                                refNode,
                                endNode,
                                field,
                                chainStr = null,
                                schemaConfig,
                                queryContext
                            )

                            createNodeWhereAndParams(
                                whereInput?.getDataForNode(refNode),
                                queryContext,
                                schemaConfig,
                                refNode,
                                endNode,
                                recurse.authValidate,
                                param.extend(refNode)
                            )
                                .let { (nodeWhere, nodeSubQueries) ->
                                    subQueries.addAll(nodeSubQueries)
                                    if (nodeWhere != null) {
                                        unionCondition = unionCondition and nodeWhere
                                    }
                                }
                            projection.addAll(recurse.projection)
                        }

                        unionProjection += Cypher.listWith(p).`in`(Cypher.listOf(p)).where(unionCondition)
                            .returning(p.project(projection))
                    }

                    var unionParts: Expression = Cypher.listWith(p)
                        .`in`(
                            Cypher.listBasedOn(rel)
                                .where(unionConditions)
                                .returning(Functions.head(unionProjection.reduce { acc, expression -> acc.add(expression) }))
                        )
                        .where(p.isNotNull)
                        .returning()

                    unionParts = arguments.options.wrapLimitAndOffset(unionParts)

                    projections += if (isArray) {
                        unionParts
                    } else {
                        Functions.head(unionParts)
                    }

                } else {
                    // NODE

                    val whereInput = arguments.where as WhereInput.NodeWhereInput?

                    val endNode = referenceNode!!.asCypherNode(
                        queryContext,
                        ChainString(schemaConfig, varName, nodeField)
                    )
                    val rel = nodeField.createQueryDslRelation(
                        Cypher.anyNode(varName.requiredSymbolicName), // TODO use varName https://github.com/neo4j-contrib/cypher-dsl/issues/595
                        endNode, arguments.directed)
                        .named(queryContext.getNextVariable(
                            chainStr?.appendOnPrevious("this")
                            ?:ChainString(schemaConfig, varName))
                        )

                    //TODO harmonize with union?
                    val recurse = createProjectionAndParams(
                        referenceNode,
                        endNode,
                        field,
                        param,
                        schemaConfig,
                        queryContext
                    )

                    val (nodeWhere, nodeSubQueries) = createNodeWhereAndParams(
                        whereInput,
                        queryContext,
                        schemaConfig,
                        referenceNode,
                        endNode,
                        recurse.authValidate,
                        param
//                            .extend(referenceNode) // TODO cleanup
                        ,
                        connectPrefixUsage
                    )

                    Cypher.with(varName)
                        .match(rel)

                    val ref = endNode.requiredSymbolicName
                    subQueries.add(
                        Cypher.with(varName)
                            .match(rel)
                            .optionalWhere(nodeWhere)
                            .withSubQueries(recurse.allSubQueries + nodeSubQueries)
                            .with(endNode.project(recurse.projection).`as`(ref))
                            .applySortingSkipAndLimit(endNode, arguments.options, recurse.sortFields, queryContext)
                            .returning(Functions.collect(ref)
                                .let { collect -> if (isArray) collect else Functions.head(collect) }
                                .`as`(ref)
                            )
                            .build()
                    )
                    projections += alias
                    projections += ref
                }

                return@forEach
            }

            if (nodeField is RelationField && isAggregate) {
                CreateFieldAggregation.createFieldAggregation(
                    varName,
                    nodeField,
                    field,
                    subQueries,
                    schemaConfig,
                    queryContext
                )?.let {
                    projections += alias
                    projections += it
                }
                return@forEach
            }

            if (nodeField is ConnectionField) {

                val returnVariable = Cypher.name(varName.name() + "_" + field.aliasOrName)

                CreateConnectionClause.createConnectionClause(
                    field,
                    nodeField,
                    queryContext,
                    varName,
                    schemaConfig,
                    returnVariable,
                )?.let {
                    subQueries += it
                    projections += alias
                    projections += returnVariable
                }
                return@forEach
            }

            if (nodeField is ScalarField) {
                projections.addAll(projectScalarField(field, nodeField, varName, shortcut = useShortcut, queryContext))
            }

        }
        return Projection(projections, authValidate, subQueries, subQueriesBeforeSort, sortFields)
    }

    private fun generateMissingOrAliasedRequiredFields(
        node: Node,
        selectedFields: Map<*, ResolveTree>
    ): Map<String, ResolveTree> {
//        TODO("take a look at this and use the selection set")
//        val requiredFields = node.computedFields
//            .filter { cf -> selectedFields.values.find { it.name == cf.fieldName } != null }
//            .mapNotNull { it.requiredFields }
//            .flatMapTo(linkedSetOf()) { it }
        val requiredFields = emptySet<String>()
        return generateMissingOrAliasedFields(requiredFields, selectedFields)
    }

    private fun createNodeWhereAndParams(
        whereInput: WhereInput?,
        context: QueryContext,
        schemaConfig: SchemaConfig,
        node: Node,
        varName: org.neo4j.cypherdsl.core.Node,
        authValidate: Condition?,
        chainStr: ChainString?,
        usePrefix: PrefixUsage = PrefixUsage.APPEND
    ): WhereResult {
        var condition: Condition? = null
        val subQueries = mutableListOf<Statement>()
        if (whereInput != null) {
            val whereResult = createWhere(node, whereInput, varName, chainStr, schemaConfig, context, usePrefix)
            condition = whereResult.predicate
            subQueries.addAll(whereResult.preComputedSubQueries)
        }

        AuthTranslator(schemaConfig, context, where = AuthTranslator.AuthOptions(varName, node, chainStr))
            .createAuth(node.auth, AuthDirective.AuthOperation.READ)
            ?.let { condition = condition and it }

        AuthTranslator(
            schemaConfig,
            context,
            allow = AuthTranslator.AuthOptions(varName, node, chainStr),
            noParamPrefix = true
        )
            .createAuth(node.auth, AuthDirective.AuthOperation.READ)
            .apocValidatePredicate(Constants.AUTH_FORBIDDEN_ERROR)
            ?.let { condition = condition and it }

        authValidate.apocValidatePredicate(Constants.AUTH_FORBIDDEN_ERROR)
            ?.let { condition = condition and it }

        return WhereResult(condition, subQueries)
    }

    class Projection(
        val projection: List<Any> = emptyList(),
        val authValidate: Condition? = null,
        val subQueries: List<Statement> = emptyList(),
        val subQueriesBeforeSort: List<Statement> = emptyList(),
        val sortFields: Map<String, Expression> = emptyMap(),
    ) {
        val allSubQueries get() = subQueriesBeforeSort + subQueries
    }

}
