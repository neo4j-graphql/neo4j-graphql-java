package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.ComputedField
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.field_arguments.RelationFieldInputArgs
import org.neo4j.graphql.translate.connection_clause.createConnectionClause
import org.neo4j.graphql.translate.projection.createInterfaceProjectionAndParams
import org.neo4j.graphql.translate.projection.projectScalarField
import org.neo4j.graphql.translate.where.createWhere
import org.neo4j.graphql.utils.ResolveTree

class ProjectionTranslator {

    fun createProjectionAndParams(
        node: Node,
        varName: org.neo4j.cypherdsl.core.Node,
        resolveTree: ResolveTree?,
        schemaConfig: SchemaConfig,
        queryContext: QueryContext,
        resolveType: Boolean = false,
        resolveId: Boolean = false,
        useShortcut: Boolean = true
    ): Projection {
        val projections = mutableListOf<Any>()

        if (resolveType) {
            projections += Constants.RESOLVE_TYPE
            projections += node.name.asCypherLiteral()
        }
        if (resolveId) {
            projections += Constants.RESOLVE_ID
            projections += Cypher.elementId(varName)
        }

        var selectedFields = resolveTree?.fieldsByTypeName?.get(node.name)
            ?: return Projection(projection = projections)

        selectedFields.values.forEach { field ->
            val nodeField = node.getField(field.name) as? ComputedField ?: return@forEach
            nodeField.annotations.customResolver?.requires?.run {
                selectedFields = selectedFields.merge(this)
            }
        }

        val subQueries = mutableListOf<Statement>()
        val subQueriesBeforeSort = mutableListOf<Statement>()

        selectedFields.values.forEach { field ->
            val alias = field.aliasOrName
            val nodeField = node.getField(field.name) ?: return@forEach

            if (nodeField is RelationField) {
                val referenceNode = nodeField.node
                val isArray = nodeField.isList()
                val arguments = RelationFieldInputArgs(nodeField, field.args)

                if (nodeField.interfaze != null) {
                    val returnVariable = Cypher.name(alias)

                    subQueries += createInterfaceProjectionAndParams(
                        field, nodeField, varName, returnVariable, queryContext, schemaConfig
                    )
                    projections += alias
                    projections += returnVariable

                } else if (nodeField.isUnion) {

                    val referenceNodes = requireNotNull(nodeField.union).nodes.values
                    val aliasVar = queryContext.getNextVariable(alias)

                    val unionSubQueries = referenceNodes.map { refNode ->
                        val endNode = refNode.asCypherNode(queryContext, queryContext.getNextVariable(refNode))
                        val nodeResult = endNode.requiredSymbolicName
                        val rel = nodeField.createQueryDslRelation(varName, endNode, arguments.directed)
                            .named(queryContext.getNextVariable(nodeField))

                        val whereInput = arguments.where as WhereInput.UnionWhereInput?


                        val (nodeWhere, nodeSubQueries) = createNodeWhereAndParams(
                            whereInput?.getDataForNode(refNode),
                            queryContext,
                            schemaConfig,
                            refNode,
                            endNode,
                        )

                        val projection = createProjectionAndParams(
                            refNode,
                            endNode,
                            field,
                            schemaConfig,
                            queryContext,
                            resolveType = true,
                            resolveId = true,
                            useShortcut = true,
                        )

                        Cypher.with(Cypher.asterisk())
                            .match(rel)
                            .optionalWhere(nodeWhere)
                            .withSubQueries(nodeSubQueries + projection.allSubQueries)
                            .with(endNode.project(projection.projection).`as`(nodeResult))
                            .returning(nodeResult.`as`(aliasVar))
                            .build()
                    }

                    subQueries += Cypher.with(varName)
                        .call(Cypher.union(unionSubQueries))
                        .with(aliasVar) // TODO remove
                        .applySortingSkipAndLimit(aliasVar, arguments.options, queryContext)
                        .returning(Cypher.collect(aliasVar).`as`(aliasVar))
                        .build()

                    projections += alias
                    projections += if (isArray) {
                        aliasVar
                    } else {
                        Cypher.head(aliasVar)
                    }

                } else {
                    // NODE

                    val whereInput = arguments.where as WhereInput.NodeWhereInput?

                    val endNode =
                        referenceNode!!.asCypherNode(queryContext, queryContext.getNextVariable(referenceNode))
                    val rel = nodeField.createQueryDslRelation(varName, endNode, arguments.directed)
                        .named(queryContext.getNextVariable(nodeField))

                    //TODO harmonize with union?
                    val recurse = createProjectionAndParams(
                        referenceNode,
                        endNode,
                        field,
                        schemaConfig,
                        queryContext
                    )

                    val (nodeWhere, nodeSubQueries) = createNodeWhereAndParams(
                        whereInput,
                        queryContext,
                        schemaConfig,
                        referenceNode,
                        endNode,
                    )

                    Cypher.with(varName)
                        .match(rel)

                    val ref = Cypher.name(alias)
                    subQueries.add(
                        Cypher.with(varName)
                            .match(rel)
                            .optionalWhere(nodeWhere)
                            .withSubQueries(recurse.allSubQueries + nodeSubQueries)
                            .with(endNode.project(recurse.projection).`as`(ref))
                            .applySortingSkipAndLimit(ref, arguments.options, queryContext, alreadyProjected = true)
                            .returning(
                                Cypher.collect(ref)
                                    .let { collect -> if (isArray) collect else Cypher.head(collect) }
                                    .`as`(ref)
                            )
                            .build()
                    )
                    projections += alias
                    projections += ref
                }

                return@forEach
            }

            if (nodeField is ConnectionField) {

                val returnVariable = Cypher.name(alias)

                createConnectionClause(
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
        return Projection(projections, subQueries, subQueriesBeforeSort)
    }

    private fun createNodeWhereAndParams(
        whereInput: WhereInput.FieldContainerWhereInput<*>?,
        context: QueryContext,
        schemaConfig: SchemaConfig,
        node: Node,
        varName: org.neo4j.cypherdsl.core.Node,
    ): WhereResult {
        var condition: Condition? = null
        val subQueries = mutableListOf<Statement>()
        if (whereInput != null) {
            val whereResult = createWhere(node, whereInput, varName, schemaConfig, context)
            condition = whereResult.predicate
            subQueries.addAll(whereResult.preComputedSubQueries)
        }

        return WhereResult(condition, subQueries)
    }

    class Projection(
        val projection: List<Any> = emptyList(),
        val subQueries: List<Statement> = emptyList(),
        val subQueriesBeforeSort: List<Statement> = emptyList(),
    ) {
        val allSubQueries get() = subQueriesBeforeSort + subQueries

        companion object {
            val EMPTY = Projection()
        }
    }

}
