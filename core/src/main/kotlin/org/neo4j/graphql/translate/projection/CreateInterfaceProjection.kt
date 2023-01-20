package org.neo4j.graphql.translate.projection

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.field_arguments.RelationFieldInputArgs
import org.neo4j.graphql.translate.AuthTranslator
import org.neo4j.graphql.translate.ProjectionTranslator
import org.neo4j.graphql.translate.where.createWhere
import org.neo4j.graphql.utils.ResolveTree

fun createInterfaceProjectionAndParams(
    resolveTree: ResolveTree,
    field: RelationField,
    nodeVariable: Node,
    withVars: List<SymbolicName>,
    returnVariable: SymbolicName,
    queryContext: QueryContext,
    schemaConfig: SchemaConfig,
): Statement {
    val fullWithVars = withVars + nodeVariable.requiredSymbolicName

    val arguments = RelationFieldInputArgs(field, resolveTree.args)
    val whereInput = arguments.where as WhereInput.InterfaceWhereInput?

    val referenceNodes = field.interfaze
        ?.implementations
        ?.values?.filter { node -> whereInput == null || whereInput.hasFilterForNode(node) }
        ?: emptyList()

    val fieldName = Cypher.name(resolveTree.name)
    val interfaceQueries = mutableListOf<Statement>()

    referenceNodes.map { refNode ->

        val param = ChainString(schemaConfig, nodeVariable, refNode)
        val relatedNode = refNode.asCypherNode(queryContext, param)

        val pattern = field.createQueryDslRelation(
            Cypher.anyNode(nodeVariable.requiredSymbolicName), // todo node is labeled if taken directly, is this a bug in CypherDSL?
            relatedNode,
            arguments.directed
        )
            .named(queryContext.getNextVariable(nodeVariable.name())) // TODO we do not need a name here

        val authAllowPredicate =
            AuthTranslator(schemaConfig, queryContext, allow = AuthTranslator.AuthOptions(relatedNode, refNode))
                .createAuth(refNode.auth, AuthDirective.AuthOperation.READ)
                ?.apocValidatePredicate(Constants.AUTH_FORBIDDEN_ERROR)

        val (wherePredicate, whereSubQueries) = createWhere(
            refNode,
            whereInput?.withPreferredOn(refNode),
            relatedNode,
            chainStr = null,
            schemaConfig,
            queryContext
        )

        val whereAuthPredicate =
            AuthTranslator(schemaConfig, queryContext, where = AuthTranslator.AuthOptions(relatedNode, refNode))
                .createAuth(refNode.auth, AuthDirective.AuthOperation.READ)

        val predicates = listOfNotNull(authAllowPredicate, wherePredicate, whereAuthPredicate)


        val recurse = ProjectionTranslator().createProjectionAndParams(
            refNode,
            relatedNode,
            resolveTree,
            chainStr = null,
            schemaConfig,
            queryContext,
            resolveType = true,
            useShortcut = false,
        )

        interfaceQueries += Cypher
            .with(fullWithVars)
            .match(pattern)
            .optionalWhere(predicates)
            .withSubQueries(whereSubQueries + recurse.allSubQueries)
            .returning(Cypher.mapOf(*recurse.projection.toTypedArray()).`as`(returnVariable))
            .build()
    }

    // TODO sorting

    val with = Cypher.with(Cypher.asterisk())
    val ongoingReading: StatementBuilder.OngoingReading = when {
        interfaceQueries.size > 1 -> with.call(Cypher.union(interfaceQueries))
        interfaceQueries.size == 1 -> with.call(interfaceQueries.first())
        else -> with
    }

    return if (field.typeMeta.type.isList()) {
        ongoingReading.returning(Functions.collect(returnVariable).`as`(returnVariable))
    } else {
        ongoingReading.returning(fieldName.`as`(returnVariable))
    }.build()

}
