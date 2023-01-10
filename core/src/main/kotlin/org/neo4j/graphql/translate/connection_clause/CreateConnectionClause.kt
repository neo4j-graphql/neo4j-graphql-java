package org.neo4j.graphql.translate.connection_clause

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.domain.inputs.connection.ConnectionWhere
import org.neo4j.graphql.domain.inputs.field_arguments.ConnectionFieldInputArgs
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.translate.AuthTranslator
import org.neo4j.graphql.translate.ProjectionTranslator
import org.neo4j.graphql.translate.projection.projectScalarField
import org.neo4j.graphql.translate.where.createConnectionWhere
import org.neo4j.graphql.utils.ResolveTree

object CreateConnectionClause {

    fun createConnectionClause(
        resolveTree: ResolveTree,
        field: ConnectionField,
        context: QueryContext,
        nodeVariable: org.neo4j.cypherdsl.core.Node,
        subQueries: MutableList<Statement>,
        schemaConfig: SchemaConfig,
    ): SymbolicName? {

        val relField = field.relationshipField

        val arguments = ConnectionFieldInputArgs(field, resolveTree.args)
        return relField.extractOnTarget(
            onNode = {
                createConnectionClause(
                    arguments,
                    resolveTree,
                    field,
                    context,
                    nodeVariable,
                    it,
                    subQueries,
                    schemaConfig
                )
            },
            onInterface = { TODO() },
            onUnion = { TODO() }
        )
    }

    private fun createConnectionClause(
        args: ConnectionFieldInputArgs,
        resolveTree: ResolveTree,
        field: ConnectionField,
        queryContext: QueryContext,
        startNode: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node,
        subQueries: MutableList<Statement>,
        schemaConfig: SchemaConfig,
    ): SymbolicName {
        val relField = field.relationshipField

        val endNode = relatedNode.asCypherNode(queryContext, ChainString(schemaConfig, startNode, relatedNode))

        val prefix = ChainString(schemaConfig, startNode, "connection", resolveTree.aliasOrName)
        val rel = relField.createQueryDslRelation(startNode, endNode, args.directed, startLeft = true)
            .named(queryContext.getNextVariable(prefix.appendOnPrevious("this")))
        val (wherePredicate, preComputedSubQueries) = createConnectionWhere(
            args.where as? ConnectionWhere.NodeConnectionWhere,
            relatedNode,
            endNode,
            relField,
            rel,
            prefix,
            schemaConfig,
            queryContext
        )

        val allowAuth =
            AuthTranslator(schemaConfig, queryContext, allow = AuthTranslator.AuthOptions(endNode, relatedNode))
                .createAuth(relatedNode.auth, AuthDirective.AuthOperation.READ)
                ?.let { it.apocValidatePredicate(Constants.AUTH_FORBIDDEN_ERROR) }

        val whereAuth =
            AuthTranslator(schemaConfig, queryContext, where = AuthTranslator.AuthOptions(endNode, relatedNode))
                .createAuth(relatedNode.auth, AuthDirective.AuthOperation.READ)

        val conditions = mutableListOf(wherePredicate, allowAuth, whereAuth)

        val innerProjection = mutableListOf<Any>()
        val projection = mutableListOf<Any>()
        val connection = resolveTree.fieldsByTypeName[field.typeMeta.type.name()]

        val edgesName = Cypher.name(Constants.EDGES_FIELD)
        val totalCount = Cypher.name(Constants.TOTAL_COUNT)
        val nestedSubQueries = preComputedSubQueries.toMutableList()

        if (connection != null) {

            if (args.sort.isNotEmpty()) {
                TODO()
            }

            connection[Constants.EDGES_FIELD]?.let { edges ->
                val relationshipFieldsByTypeName = edges.fieldsByTypeName[field.relationshipTypeName]
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
                    innerProjection.addAll(projectScalarField(it, scalarField, rel))
                }

                // TODO extraFields (sorting)

                relationshipFieldsByTypeName?.get(Constants.NODE_FIELD)?.let {

                    val (projectionSubQueries, nestedProjection, authValidate) = ProjectionTranslator()
                        .createProjectionAndParams(
                            relatedNode,
                            endNode,
                            it,
                            prefix,
                            schemaConfig,
                            emptyMap(),
                            queryContext,
                            useShortcut = false
                        )
                    conditions.add(authValidate)
                    nestedSubQueries.addAll(projectionSubQueries)
                    innerProjection += it.aliasOrName
                    innerProjection += Cypher.mapOf(*nestedProjection.toTypedArray())
                }


                projection += edges.aliasOrName
                projection += edgesName
            }


            // TODO use block below
            //  connection[Constants.TOTAL_COUNT]?.let {
            //      projection += it.aliasOrName
            //      projection += Functions.size(edgesName).`as`(totalCount)
            //  }
                projection += connection[Constants.TOTAL_COUNT]?.aliasOrName?:Constants.TOTAL_COUNT
                projection += totalCount


            connection[Constants.PAGE_INFO]?.let { TODO() }
        }


        val edge = Cypher.name(Constants.EDGE_FIELD)
        val resultName = Cypher.name(startNode.name() + "_" + resolveTree.aliasOrName)
        subQueries += Cypher.with(startNode)
            .match(rel)
            .optionalWhere(conditions)
            .withSubQueries(nestedSubQueries)
            .with(Cypher.mapOf(*innerProjection.toTypedArray()).`as`(edge))
            .with(Functions.collect(edge).`as`(edgesName))
            .with(edgesName as IdentifiableElement, Functions.size(edgesName).`as`(totalCount))
            .returning(Cypher.mapOf(*projection.toTypedArray()).`as`(resultName))
            .build()

        return resultName
    }
}
