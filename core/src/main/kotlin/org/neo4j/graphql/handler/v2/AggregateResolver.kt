package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.fields.PrimitiveField
import org.neo4j.graphql.domain.fields.TemporalField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.filter.FulltextPerIndex
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.handler.projection.createDatetimeExpression
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.AugmentationHandlerV2
import org.neo4j.graphql.translate.AuthTranslator
import org.neo4j.graphql.translate.TopLevelMatchTranslator
import org.neo4j.graphql.utils.ResolveTree


class AggregateResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandlerV2(ctx) {

        override fun augmentNode(node: Node): AugmentedField? {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.READ)) {
                return null
            }
            val aggregationSelection = addAggregationSelectionType(node)
            val coordinates =
                addQueryField(node.rootTypeFieldNames.aggregate, aggregationSelection.asRequiredType()) { args ->
                    generateWhereIT(node)?.let { args += inputValue(Constants.WHERE, it.asType()) }
                    generateFulltextIT(node)?.let { args += inputValue(Constants.FULLTEXT, it.asType()) }
                }
            return AugmentedField(coordinates, AggregateResolver(ctx.schemaConfig, node))
        }
    }

    private class InputArguments(node: Node, args: Map<String, *>) {
        val where = args[Constants.WHERE]?.let { WhereInput.NodeWhereInput(node, Dict(it)) }
        val fulltext = args[Constants.FULLTEXT]?.let { FulltextPerIndex(Dict(it)) }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val resolveTree = ResolveTree.resolve(env)

        val arguments = InputArguments(node, resolveTree.args)
        val queryContext = env.queryContext()

        val dslNode = node.asCypherNode(queryContext, variable)

        val authPredicates =
            AuthTranslator(schemaConfig, queryContext, allow = AuthTranslator.AuthOptions(dslNode, node))
            .createAuth(node.auth, AuthDirective.AuthOperation.READ)
            ?.let { it.apocValidatePredicate(Constants.AUTH_FORBIDDEN_ERROR) }

// TODO harmonize with read
        var ongoingReading = TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
            .translateTopLevelMatch(
                node,
                dslNode,
                arguments.fulltext,
                arguments.where,
                AuthDirective.AuthOperation.READ,
                authPredicates
            )


        val selection = resolveTree.fieldsByTypeName[node.aggregateTypeNames.selection]
        val chainStr = ChainString(schemaConfig, dslNode)
        var authValidate: Condition? = null
        val projection = mutableListOf<Any>()

        selection?.values?.forEach { fieldSelection ->
            val alias = fieldSelection.aliasOrName
            val param = chainStr.extend(alias)

            if (fieldSelection.name == Constants.COUNT) {
                projection += alias
                projection += Functions.count(dslNode)
            }

            val nodeField = node.getField(fieldSelection.name) as? PrimitiveField ?: return@forEach
            nodeField.auth?.let { auth ->
                AuthTranslator(
                    schemaConfig,
                    queryContext,
                    allow = AuthTranslator.AuthOptions(dslNode, node, param)
                )
                    .createAuth(auth, AuthDirective.AuthOperation.READ)
                    ?.let { authValidate = authValidate and it }
            }

            val aggregateFields = fieldSelection.fieldsByTypeName[nodeField.getAggregationSelectionLibraryTypeName()]
            val property = dslNode.property(nodeField.dbPropertyName)

            val thisProjections = mutableListOf<Any>()
            aggregateFields?.values?.forEach { aggregateField ->
                val value = when (aggregateField.name) {
                    Constants.MIN, Constants.SHORTEST -> Functions.min(property)
                    Constants.MAX, Constants.LONGEST -> Functions.max(property)
                    Constants.AVERAGE -> Functions.avg(property)
                    Constants.SUM -> Functions.sum(property)
                    else -> error("unknown aggregation function " + aggregateField.name)
                }
                thisProjections += aggregateField.aliasOrName

                if (nodeField is TemporalField && nodeField.typeMeta.type.name() == Constants.DATE_TIME) {
                    // TODO remove toString?
                    thisProjections += createDatetimeExpression(nodeField, Functions.toString(value))
                } else if (nodeField.typeMeta.type.name() == Constants.Types.String.name()) {
                    val current = Cypher.name("current")
                    val aggVar = Cypher.name("aggVar")
                    thisProjections += Functions.reduce(current)
                        .`in`(Functions.collect(property))
                        .map(
                            Cypher.caseExpression()
                                .`when`(Functions.size(current).let {
                                    when (aggregateField.name) {
                                        Constants.SHORTEST -> it.lt(Functions.size(aggVar))
                                        Constants.LONGEST -> it.gt(Functions.size(aggVar))
                                        else -> error("only ${Constants.SHORTEST} or ${Constants.LONGEST} is supported for string aggregation")
                                    }
                                })
                                .then(current)
                                .elseDefault(aggVar)
                        )
                        .accumulateOn(aggVar)
                        .withInitialValueOf(Cypher.valueAt(Functions.collect(property), 0))
                } else {
                    thisProjections += value
                }

            }
            projection += alias
            projection += Cypher.mapOf(*thisProjections.toTypedArray())
        }

        authValidate?.let {
            ongoingReading = ongoingReading.apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
        }
        return ongoingReading
            .returning(Cypher.mapOf(*projection.toTypedArray()))
            .build()
    }
}

