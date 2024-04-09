package org.neo4j.graphql.translate.connection_clause

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthenticationDirective
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.field_arguments.ConnectionFieldInputArgs
import org.neo4j.graphql.translate.ProjectionTranslator
import org.neo4j.graphql.translate.checkAuthentication
import org.neo4j.graphql.translate.projection.projectScalarField
import org.neo4j.graphql.translate.where.PrefixUsage
import org.neo4j.graphql.utils.ResolveTree

object CreateConnectionClause {

    fun createConnectionClause(
        resolveTree: ResolveTree,
        field: ConnectionField,
        context: QueryContext,
        nodeVariable: org.neo4j.cypherdsl.core.Node,
        schemaConfig: SchemaConfig,
        returnVariable: SymbolicName,
        usePrefix: PrefixUsage = PrefixUsage.APPEND // TODO inline
    ): Statement? {

        val relField = field.relationshipField
        val prefix = ChainString(schemaConfig, nodeVariable, "connection", field)
        val arguments = ConnectionFieldInputArgs(field, resolveTree.args)
        return relField.extractOnTarget(
            onNode = {
                createConnectionClauseForNode(
                    arguments,
                    resolveTree,
                    field,
                    context,
                    nodeVariable,
                    it,
                    schemaConfig,
                    usePrefix,
                    returnVariable,
                    prefix,
                )
            },
            onInterface = {
                createConnectionClauseForUnions(
                    it.implementations.values, arguments,
                    resolveTree,
                    field,
                    context,
                    nodeVariable,
                    schemaConfig,
                    usePrefix,
                    returnVariable,
                    prefix,
                )
            },
            onUnion = {
                createConnectionClauseForUnions(
                    it.nodes.values, arguments,
                    resolveTree,
                    field,
                    context,
                    nodeVariable,
                    schemaConfig,
                    usePrefix,
                    returnVariable,
                    prefix,
                )
            }
        )
    }

    class OrderAndLimit(
        val sortItems: List<SortItem>,
        val offset: Int?,
        val limit: Int?,
    )

    private fun cursorToOffset(cursor: String): Int {
        TODO("Not yet implemented")
    }

    private fun createConnectionSortAndLimit(
        arguments: ConnectionFieldInputArgs,
        relationshipRef: (Array<String>) -> Property,
        nodeRef: (Array<String>) -> Property,
        limit: Int?,
        fieldOverrides: Map<String, Expression>,
        ignoreSkipLimit: Boolean = false,
    ): OrderAndLimit? {

        val sortItems = arguments.sort.flatMap { sortField ->
            val nodeSorts = sortField.node
                ?.getCypherSortFields(nodeRef, fieldOverrides)
                ?: emptyList()
            val edgeSorts = sortField.edge
                ?.getCypherSortFields(relationshipRef, fieldOverrides)
                ?: emptyList()
            nodeSorts + edgeSorts
        }

        var calculatedLimit = arguments.first
        var offset = arguments.after?.let { cursorToOffset(it) + 1 }
        if (limit != null && (calculatedLimit == null || limit < calculatedLimit)) {
            calculatedLimit = limit
        }
        if (ignoreSkipLimit) {
            calculatedLimit = null
            offset = null
        }
        if (sortItems.isEmpty() && calculatedLimit == null && limit == null) {
            return null
        }
        return OrderAndLimit(sortItems, offset, calculatedLimit)
    }

    private fun createConnectionClauseForUnions(
        nodes: Collection<Node>,
        arguments: ConnectionFieldInputArgs,
        resolveTree: ResolveTree,
        field: ConnectionField,
        context: QueryContext,
        nodeVariable: org.neo4j.cypherdsl.core.Node,
        schemaConfig: SchemaConfig,
        usePrefix: PrefixUsage,
        returnVariable: SymbolicName,
        prefix: ChainString,
    ): Statement? {

        val collectUnionVariable = Cypher.name("edge")

        val sortFieldOverrides = mutableMapOf<String, Expression>()
        val subQueries = nodes.map {
            createEdgeSubquery(
                arguments,
                resolveTree,
                field,
                context,
                nodeVariable,
                it,
                schemaConfig,
                usePrefix,
                collectUnionVariable,
                resolveType = true,
                ignoreSort = true,
                sortFieldOverrides,
            )
                .returning(collectUnionVariable)
                .build()

        }
            .takeIf { it.isNotEmpty() }
            ?: return null

        return Cypher
            .with(nodeVariable)
            .call(Cypher.union(subQueries))
            .collectAndSort(
                arguments,
                collectUnionVariable,
                sortFieldOverrides,
                context,
                returnVariable,
                prefix,
                skipNestedSubquery = true
            )
    }

    private fun createConnectionClauseForNode(
        args: ConnectionFieldInputArgs,
        resolveTree: ResolveTree,
        field: ConnectionField,
        queryContext: QueryContext,
        startNode: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node,
        schemaConfig: SchemaConfig,
        usePrefix: PrefixUsage = PrefixUsage.NONE,
        returnVariable: SymbolicName,
        prefix: ChainString,
    ): Statement {

        val edgeItem = Cypher.name("edge")
        val sortFieldOverrides = mutableMapOf<String, Expression>()

        checkAuthentication(
            relatedNode,
            queryContext,
            field = null,
            AuthenticationDirective.AuthenticationOperation.READ
        )

        val edgeSubquery = createEdgeSubquery(
            args,
            resolveTree,
            field,
            queryContext,
            startNode,
            relatedNode,
            schemaConfig,
            usePrefix,
            edgeItem,
            resolveType = false,
            ignoreSort = false,
            sortFieldOverrides,
        )

        return edgeSubquery
            .collectAndSort(
                args,
                edgeItem,
                sortFieldOverrides,
                queryContext,
                returnVariable,
                prefix,
                skipNestedSubquery = false
            )
    }

    private fun ExposesWith.collectAndSort(
        args: ConnectionFieldInputArgs,
        edgeItem: SymbolicName,
        sortFieldOverrides: Map<String, Expression>,
        queryContext: QueryContext,
        returnVariable: SymbolicName,
        prefix: ChainString,
        skipNestedSubquery: Boolean // TODO having this value == triue seems to be a bug
    ): Statement {
        val order = createConnectionSortAndLimit(
            args,
            edgeItem::property,
            edgeItem.property(Constants.NODE_FIELD)::property,
            null,
            sortFieldOverrides,
        )

        val edgesList = Cypher.name("edges")
        val totalCount = Cypher.name("totalCount")

        return this.with(Functions.collect(edgeItem).`as`(edgesList))
            .with(edgesList as IdentifiableElement, Functions.size(edgesList).`as`(totalCount))
            .let {
                if (order != null) {
                    if (skipNestedSubquery) {
                        it
                            .unwind(edgesList).`as`(edgeItem)
                            .with(edgeItem, totalCount)
                            .applySortingSkipAndLimit(order, queryContext)
                            .with(Functions.collect(edgeItem).`as`(edgesList) as IdentifiableElement, totalCount)
                    } else {
                        val sortedEdges = queryContext.getNextVariable(prefix.appendOnPrevious("var"))
                        it.call(
                            Cypher
                                .with(edgesList)
                                .unwind(edgesList).`as`(edgeItem)
                                .with(edgeItem)
                                .applySortingSkipAndLimit(order, queryContext, prefix.appendOnPrevious("param"))
                                .returning(Functions.collect(edgeItem).`as`(sortedEdges))
                                .build()
                        )
                            .with(sortedEdges.`as`(edgesList) as IdentifiableElement, totalCount)
                    }
                } else {
                    it
                }
            }
            .returning(
                Cypher.mapOf(
                    Constants.EDGES_FIELD, edgesList,
                    Constants.TOTAL_COUNT, totalCount
                ).`as`(returnVariable)
            )
            .build()
    }

    private fun createEdgeSubquery(
        args: ConnectionFieldInputArgs,
        resolveTree: ResolveTree,
        field: ConnectionField,
        queryContext: QueryContext,
        startNode: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node,
        schemaConfig: SchemaConfig,
        usePrefix: PrefixUsage = PrefixUsage.NONE,
        returnVariable: SymbolicName,
        resolveType: Boolean = false,
        ignoreSort: Boolean = false,
        sortFieldOverrides: MutableMap<String, Expression>
    ): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
        val relField = field.relationshipField

        val endNode = relatedNode.asCypherNode(queryContext, ChainString(schemaConfig, startNode, relatedNode))

        val prefix = ChainString(schemaConfig, startNode, "connection", resolveTree.aliasOrName)
        TODO()
//        val rel = relField.createQueryDslRelation(
//            Cypher.anyNode()
//                .named(startNode.requiredSymbolicName), // TODO https://github.com/neo4j-contrib/cypher-dsl/issues/589
//            endNode,
//            args.directed,
//            startLeft = true
//        )
//            .named(queryContext.getNextVariable(prefix.appendOnPrevious("this")))
//
//        val whereInput = when (args.where) {
//            is ConnectionWhere.ImplementingTypeConnectionWhere<*> -> args.where
//            is ConnectionWhere.UnionConnectionWhere -> args.where.getDataForNode(relatedNode)
//            null -> null
//        }
//
//        var where = createConnectionWhere(
//            whereInput,
//            relatedNode,
//            endNode,
//            relField,
//            rel,
//            prefix,
//            schemaConfig,
//            queryContext,
//            usePrefix
//        )
//
//        where = where and AuthorizationFactory.getAuthConditions(
//            relatedNode,
//            endNode,
//            fields = null,
//            schemaConfig,
//            queryContext,
//            AuthorizationDirective.AuthorizationOperation.READ
//        )
//
//
//        val projection = createEdgeProjection(
//            resolveTree,
//            field,
//            relField,
//            rel,
//            queryContext,
//            relatedNode,
//            endNode,
//            prefix,
//            schemaConfig,
//            resolveType,
//            args,
//            returnVariable,
//        )
////        sortFieldOverrides.putAll(projection.sortFields)
//
//        where = where and projection.authValidate
//        val subqueries = where.preComputedSubQueries + projection.allSubQueries
//
//        val order = if (!ignoreSort) {
//            // we ignore limit here to avoid breaking totalCount
//            createConnectionSortAndLimit(
//                args,
//                rel::property,
//                endNode::property,
//                null,
//                emptyMap(),
//                ignoreSkipLimit = true
//            )
//        } else {
//            null
//        }
//        return Cypher.with(startNode)
//            .match(rel)
//            .let {
//                if (subqueries.isEmpty()) {
//                    it
//                        .optionalWhere(where.predicate)
//                } else {
//                    it.withSubQueries(subqueries)
//                        .with(Cypher.asterisk())
//                        .optionalWhere(where.predicate)
//                }
//            }
////            .applySortingSkipAndLimit(order, queryContext, prefix, listOf(rel, endNode))
////            .withSubQueries(projection.subQueries)
//            .with(
//                Cypher.collect(
//                    Cypher.mapOf(
//                        Constants.NODE_FIELD, endNode.asExpression(),
//                        Constants.RELATIONSHIP_FIELD, rel.asExpression()
//                    )
//                ).`as`(returnVariable)
//            )
    }

    private fun createEdgeProjection(
        resolveTree: ResolveTree,
        field: ConnectionField,
        relField: RelationField,
        rel: Relationship,
        queryContext: QueryContext,
        relatedNode: Node,
        endNode: org.neo4j.cypherdsl.core.Node,
        prefix: ChainString,
        schemaConfig: SchemaConfig,
        resolveType: Boolean,
        args: ConnectionFieldInputArgs,
        returnVariable: SymbolicName,
    ): ProjectionTranslator.Projection {
        val subQueries = mutableListOf<Statement>()
        val projection = mutableListOf<Any>()
        var authCondition: Condition? = null


        val connection = resolveTree.fieldsByTypeName[field.typeMeta.type.name()]
            ?: return ProjectionTranslator.Projection()
        val sortFields = mutableMapOf<String, Expression>()

        val edges = connection[Constants.EDGES_FIELD]
        if (edges != null) {
            val relationshipFieldsByTypeName =
                edges.fieldsByTypeName[field.relationshipField.namings.relationshipFieldTypename2]
            val relationshipProperties = relationshipFieldsByTypeName
                ?.toMutableMap()
                ?.also {
                    it.remove(Constants.NODE_FIELD)
                    it.remove(Constants.CURSOR_FIELD)
                }
                ?: emptyMap()

            relationshipProperties.values.forEach {
                val scalarField =
                    relField.properties?.getField(it.name) as? ScalarField ?: error("expect only scalar fields")
                projection.addAll(projectScalarField(it, scalarField, rel, queryContext = queryContext))
            }

            args.sort
                .mapNotNull { connectionSort -> connectionSort.edge?.map { it.key } }
                .flatten()
                .forEach { extraField ->
                    val projectedAlias = relationshipFieldsByTypeName?.getAliasOfField(extraField)
                    if (projectedAlias == null) {
                        projection += extraField
                        projection += rel.property(extraField)
                    } else {
                        sortFields[extraField] = returnVariable.property(projectedAlias)
                    }
                }

            relationshipFieldsByTypeName?.get(Constants.NODE_FIELD)?.let { nodeResolveTree ->

                val mergedResolveTree = nodeResolveTree.extend(
                    relatedNode.name,
                    args.sort
                        .mapNotNull { connectionSort -> connectionSort.node?.map { it.key } }
                        .flatten()
                )

                val nodeProjection = ProjectionTranslator()
                    .createProjectionAndParams(
                        relatedNode,
                        endNode,
                        mergedResolveTree,
                        prefix,
                        schemaConfig,
                        queryContext,
                        useShortcut = false,
                        resolveType = resolveType
                    )
                authCondition = nodeProjection.authValidate
                subQueries.addAll(nodeProjection.allSubQueries)
                projection += mergedResolveTree.aliasOrName
                projection += Cypher.mapOf(*nodeProjection.projection.toTypedArray())
            }
        } else {
            // This ensures that totalCount calculation is accurate if edges are not asked for
            projection += Constants.NODE_FIELD
            projection += Cypher.mapOf(
                Constants.RESOLVE_TYPE, relatedNode.name.asCypherLiteral()
            )

        }
        return ProjectionTranslator.Projection(projection, authCondition, subQueries, sortFields = sortFields)
    }

}
