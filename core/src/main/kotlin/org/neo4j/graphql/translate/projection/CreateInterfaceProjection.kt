package org.neo4j.graphql.translate.projection

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.field_arguments.RelationFieldInputArgs
import org.neo4j.graphql.translate.ProjectionTranslator
import org.neo4j.graphql.translate.where.createWhere
import org.neo4j.graphql.utils.ResolveTree

fun createInterfaceProjectionAndParams(
    resolveTree: ResolveTree,
    field: RelationField,
    nodeVariable: Node,
    returnVariable: SymbolicName,
    queryContext: QueryContext,
    schemaConfig: SchemaConfig,
): Statement {

    val arguments = RelationFieldInputArgs(field, resolveTree.args)
    val whereInput = arguments.where as WhereInput.InterfaceWhereInput?

    val referenceNodes = field.interfaze
        ?.implementations
        ?.values?.filter { whereInput == null || whereInput.hasPredicates() }
        ?: emptyList()

    val interfaceQueries = createUnionQueries(
        referenceNodes,
        resolveTree,
        returnVariable,
        queryContext,
        schemaConfig,
        whereInput
    ) { relatedNode ->

        val pattern = field.createQueryDslRelation(nodeVariable, relatedNode, arguments.directed)
            .named(queryContext.getNextVariable(field))

        Cypher
            .with(Cypher.asterisk())
            .match(pattern)
    }

    val with = Cypher.with(nodeVariable)
    var ongoingReading: StatementBuilder.OngoingReading = when {
        interfaceQueries.size > 1 -> with.call(Cypher.union(interfaceQueries))
        interfaceQueries.size == 1 -> with.call(interfaceQueries.first())
        else -> with
    }
    ongoingReading = ongoingReading
        .with(returnVariable)
        .applySortingSkipAndLimit(returnVariable, arguments.options, queryContext)

    return if (field.isList()) {
        ongoingReading.returning(Cypher.collect(returnVariable).`as`(returnVariable))
    } else {
        ongoingReading.returning(Cypher.head(Cypher.collect(returnVariable)).`as`(returnVariable))
    }.build()

}

fun createUnionQueries(
    referenceNodes: Collection<org.neo4j.graphql.domain.Node>,
    resolveTree: ResolveTree,
    returnVariable: SymbolicName,
    queryContext: QueryContext,
    schemaConfig: SchemaConfig,
    whereInput: WhereInput?,
    matchFactory: (Node) -> StatementBuilder.OngoingReadingWithoutWhere
): List<Statement> {

    val interfaceQueries = statements()

    referenceNodes.map { refNode ->
        val relatedNode = refNode.asCypherNode(queryContext, queryContext.getNextVariable(refNode))

        val (wherePredicate, whereSubQueries) = createWhere(
            refNode,
            whereInput,
            relatedNode,
            schemaConfig,
            queryContext
        )

        val predicates = listOfNotNull(wherePredicate)

        val recurse = ProjectionTranslator().createProjectionAndParams(
            refNode,
            relatedNode,
            resolveTree,
            schemaConfig,
            queryContext,
            resolveType = true,
            resolveId = true,
            useShortcut = true,
        )

        interfaceQueries += matchFactory(relatedNode)
            .optionalWhere(predicates)
            .withSubQueries(whereSubQueries + recurse.allSubQueries)
            .with(relatedNode.project(recurse.projection).`as`(relatedNode.requiredSymbolicName))
            .returning(relatedNode.`as`(returnVariable.value))
            .build()
    }

    return interfaceQueries
}

private fun statements() = mutableListOf<Statement>()
