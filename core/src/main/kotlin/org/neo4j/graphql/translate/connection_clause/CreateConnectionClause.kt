package org.neo4j.graphql.translate.connection_clause

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.schema.model.inputs.field_arguments.ConnectionFieldInputArgs
import org.neo4j.graphql.translate.ProjectionTranslator
import org.neo4j.graphql.translate.projection.projectScalarField
import org.neo4j.graphql.translate.where.createConnectionWhere
import org.neo4j.graphql.utils.ResolveTree

fun createConnectionClause(
    resolveTree: ResolveTree,
    field: ConnectionField,
    context: QueryContext,
    nodeVariable: org.neo4j.cypherdsl.core.Node,
    schemaConfig: SchemaConfig,
    returnVariable: SymbolicName,
): Statement? {

    val relField = field.relationshipField
    val arguments = ConnectionFieldInputArgs(field, resolveTree.args)
    return relField.extractOnTarget(
        onNode = {
            createConnectionClauseForSingleNode(
                arguments,
                resolveTree,
                field,
                context,
                nodeVariable,
                it,
                schemaConfig,
                returnVariable,
            )
        },
        onInterface = {
            createConnectionClauseForMultipleNodes(
                it.implementations.values, arguments,
                resolveTree,
                field,
                context,
                nodeVariable,
                schemaConfig,
                returnVariable,
            )
        },
        onUnion = {
            createConnectionClauseForMultipleNodes(
                it.nodes.values, arguments,
                resolveTree,
                field,
                context,
                nodeVariable,
                schemaConfig,
                returnVariable,
            )
        }
    )
}

private fun createConnectionClauseForMultipleNodes(
    nodes: Collection<Node>,
    arguments: ConnectionFieldInputArgs,
    resolveTree: ResolveTree,
    field: ConnectionField,
    context: QueryContext,
    nodeVariable: org.neo4j.cypherdsl.core.Node,
    schemaConfig: SchemaConfig,
    returnVariable: SymbolicName,
): Statement? {

    val collectUnionVariable = Cypher.name("edge")

    val subQueries = nodes.map {
        createEdgeSubquery(
            arguments,
            resolveTree,
            field,
            context,
            nodeVariable,
            it,
            schemaConfig,
            collectUnionVariable,
        )
            .returning(collectUnionVariable)
            .build()

    }
        .takeIf { it.isNotEmpty() }
        ?: return null

    val totalCount = Cypher.name("totalCount")
    val edges = Cypher.name("edges")
    var targetEdges = edges
    return Cypher
        .with(nodeVariable)
        .call(Cypher.union(subQueries))
        .with(Cypher.collect(collectUnionVariable).`as`(edges))
        .with(edges, Cypher.size(edges).`as`(totalCount))
        .let {

            val edge = Cypher.name("edge")
            if (arguments.options.isEmpty()) {
                it
            } else {
                targetEdges = Cypher.name(context.getNextVariableName("sortedEdges"))
                it.call(
                    Cypher
                        .with(edges)
                        .unwind(edges).`as`(edge)
                        .applySortingSkipAndLimit(
                            arguments.options,
                            edge.property(Constants.NODE_FIELD),
                            edge.property(Constants.PROPERTIES_FIELD),
                            context,
                            withVars = listOf(edge)
                        )
                        .returning(Cypher.collect(edge).`as`(targetEdges))
                        .build()
                )
            }
        }
        .returning(
            Cypher.mapOf(
                Constants.EDGES_FIELD, targetEdges,
                Constants.TOTAL_COUNT, totalCount
            ).`as`(returnVariable)
        )
        .build()
}

private fun createConnectionClauseForSingleNode(
    args: ConnectionFieldInputArgs,
    resolveTree: ResolveTree,
    field: ConnectionField,
    queryContext: QueryContext,
    startNode: org.neo4j.cypherdsl.core.Node,
    relatedNode: Node,
    schemaConfig: SchemaConfig,
    returnVariable: SymbolicName,
): Statement {


    val edgesItem = Cypher.name("edges")

    val relField = field.relationshipField
    val endNode =
        relatedNode.asCypherNode(queryContext, queryContext.getNextVariable(relatedNode))

    val rel = relField.createQueryDslRelation(startNode, endNode, args.directed)
        .named(queryContext.getNextVariable(relField))
    val whereInput = when (args.where) {
        is ConnectionWhere.ImplementingTypeConnectionWhere<*> -> args.where
        is ConnectionWhere.UnionConnectionWhere -> args.where.getDataForNode(relatedNode)
        null -> null
    }
    val where = createConnectionWhere(
        whereInput,
        relatedNode,
        endNode,
        relField,
        rel,
        schemaConfig,
        queryContext,
    )

    val subqueries = where.preComputedSubQueries
    val totalCount = Cypher.name("totalCount")
    val projectedEdges = returnVariable.concat("Edges")

    return Cypher.with(startNode)
        .match(rel)
        .let {
            if (subqueries.isEmpty()) {
                it.optionalWhere(where.predicate)
            } else {
                it.withSubQueries(subqueries)
                    .with(Cypher.asterisk())
                    .optionalWhere(where.predicate)
            }
        }
        .with(
            Cypher.collect(
                Cypher.mapOf(
                    Constants.NODE_FIELD, endNode.asExpression(),
                    Constants.RELATIONSHIP_FIELD, rel.asExpression(),
                )
            ).`as`(edgesItem)
        )
        .with(edgesItem, Cypher.size(edgesItem).`as`(totalCount))
        .let {
            val edgeItem = Cypher.name("edge")
            val relName = rel.requiredSymbolicName

            val projection = createEdgeProjection(
                resolveTree,
                field,
                relField,
                relName,
                queryContext,
                relatedNode,
                endNode,
                schemaConfig,
                args = args,
                resolveId = false,
            )

            it.call(
                Cypher
                    .with(edgesItem)
                    .unwind(edgesItem).`as`(edgeItem)
                    .with(
                        edgeItem.property(Constants.NODE_FIELD).`as`(endNode.name()),
                        edgeItem.property(Constants.RELATIONSHIP_FIELD).`as`(relName)
                    )
                    .withSubQueries(projection.subQueriesBeforeSort)
                    .applySortingSkipAndLimit(
                        args.options.merge(relField.implementingType),
                        endNode,
                        relName,
                        queryContext,
                    )
                    .withSubQueries(projection.subQueries)
                    .returning(
                        Cypher.collect(
                            Cypher.mapOf(
                                *projection.projection.toTypedArray()
                            )
                        ).`as`(projectedEdges)
                    )
                    .build()
            )
        }
        .returning(
            Cypher.mapOf(
                Constants.EDGES_FIELD, projectedEdges,
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
    returnVariable: SymbolicName
): StatementBuilder.OrderableOngoingReadingAndWithWithoutWhere {
    val relField = field.relationshipField

    val endNode = relatedNode.asCypherNode(queryContext, queryContext.getNextVariable(relatedNode))

    val rel = relField.createQueryDslRelation(startNode, endNode, args.directed)
        .named(queryContext.getNextVariable(relField))

    val whereInput = when (args.where) {
        is ConnectionWhere.ImplementingTypeConnectionWhere<*> -> args.where
        is ConnectionWhere.UnionConnectionWhere -> args.where.getDataForNode(relatedNode)
        null -> null
    }

    val where = createConnectionWhere(
        whereInput,
        relatedNode,
        endNode,
        relField,
        rel,
        schemaConfig,
        queryContext,
    )

    val projection = createEdgeProjection(
        resolveTree,
        field,
        relField,
        rel,
        queryContext,
        relatedNode,
        endNode,
        schemaConfig,
        args,
        resolveId = true
    )

    val subqueries = where.preComputedSubQueries
    return Cypher.with(startNode)
        .match(rel)
        .let {
            if (subqueries.isEmpty()) {
                it.optionalWhere(where.predicate)
            } else {
                it.withSubQueries(subqueries)
                    .with(Cypher.asterisk())
                    .optionalWhere(where.predicate)
            }
        }
        .with(
            Cypher.mapOf(*projection.projection.toTypedArray()).`as`(returnVariable)
        )
}

private fun createEdgeProjection(
    resolveTree: ResolveTree,
    field: ConnectionField,
    relField: RelationField,
    rel: PropertyAccessor,
    queryContext: QueryContext,
    relatedNode: Node,
    endNode: org.neo4j.cypherdsl.core.Node,
    schemaConfig: SchemaConfig,
    args: ConnectionFieldInputArgs,
    resolveId: Boolean,
): ProjectionTranslator.Projection {
    val subQueries = mutableListOf<Statement>()
    val projection = mutableListOf<Any>()


    var adjustedResolveTree = resolveTree

    val edgeSortProperties = args.options.sort.flatMap { it.edge?.keys ?: emptyList() }
    val nodeSortProperties = args.options.sort.flatMap { it.node?.keys ?: emptyList() }
    if (edgeSortProperties.isNotEmpty() || nodeSortProperties.isNotEmpty()) {
        adjustedResolveTree = adjustedResolveTree.extend {
            select(Constants.EDGES_FIELD, field.relationshipField.namings.connectionFieldName) {
                if (edgeSortProperties.isNotEmpty()) {
                    select(Constants.PROPERTIES_FIELD, field.relationshipField.namings.relationshipFieldTypename) {
                        edgeSortProperties.forEach { name ->
                            select(name, field.relationshipField.properties?.typeName!!)
                        }
                    }
                }
                if (nodeSortProperties.isNotEmpty()) {
                    select(Constants.NODE_FIELD, field.relationshipField.namings.relationshipFieldTypename) {
                        nodeSortProperties.forEach { name ->
                            select(name, relatedNode.name)
                        }
                    }
                }
            }
        }
    }

    val connection = adjustedResolveTree.fieldsByTypeName[field.relationshipField.namings.connectionFieldName]
        ?: return ProjectionTranslator.Projection.EMPTY

    connection.forEachField(Constants.EDGES_FIELD) { edges ->
        val relationshipFieldsByTypeName =
            edges.fieldsByTypeName[field.relationshipField.namings.relationshipFieldTypename]

        relationshipFieldsByTypeName?.forEachField(Constants.PROPERTIES_FIELD) { edgeResolveTree ->

            val edgeProjection =
                projectEdgeProperties(edgeResolveTree, relField, rel, queryContext)
            projection += edgeResolveTree.aliasOrName
            projection += edgeProjection
        }

        relationshipFieldsByTypeName?.forEachField(Constants.NODE_FIELD) { nodeResolveTree ->

            val nodeProjection = ProjectionTranslator()
                .createProjectionAndParams(
                    relatedNode,
                    endNode,
                    nodeResolveTree,
                    schemaConfig,
                    queryContext,
                    resolveType = true,
                    useShortcut = false
                )
            subQueries.addAll(nodeProjection.allSubQueries)

            val projection1 = nodeProjection.projection.toMutableList()
            if (resolveId) {
                projection1 += Constants.RESOLVE_ID
                projection1 += Cypher.elementId(endNode)
            }
            projection += nodeResolveTree.aliasOrName
            projection += Cypher.mapOf(*projection1.toTypedArray())
        }
    }

    if (projection.isEmpty()) {
        // This ensures that totalCount calculation is accurate if edges are not asked for
        projection += Constants.NODE_FIELD
        projection += Cypher.mapOf(
            Constants.RESOLVE_ID, Cypher.elementId(endNode),
            Constants.RESOLVE_TYPE, relatedNode.name.asCypherLiteral()
        )
    }
    return ProjectionTranslator.Projection(projection, subQueries)
}


private fun projectEdgeProperties(
    propertiesFieldSelection: ResolveTree,
    relField: RelationField,
    rel: PropertyAccessor,
    queryContext: QueryContext
): MapExpression {
    val edgeTypeName = requireNotNull(relField.properties).typeName
    val relationshipProperties = propertiesFieldSelection.fieldsByTypeName[edgeTypeName]

    val projection = mutableListOf<Any>()
    projection += Constants.RESOLVE_TYPE
    projection += edgeTypeName.asCypherLiteral()

    relationshipProperties?.values?.forEach {
        val scalarField = relField.properties.getField(it.name) as? ScalarField
            ?: error("expect only scalar fields")
        projection.addAll(projectScalarField(it, scalarField, rel, queryContext = queryContext))
    }
    return Cypher.mapOf(*projection.toTypedArray())
}
