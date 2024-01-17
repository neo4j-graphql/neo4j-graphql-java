package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.InputValueDefinition
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Entity
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.TemporalField
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.filter.FulltextInput
import org.neo4j.graphql.schema.model.outputs.aggregate.AggregationSelection
import org.neo4j.graphql.translate.AuthTranslator
import org.neo4j.graphql.translate.TopLevelMatchTranslator
import org.neo4j.graphql.translate.projection.createDatetimeExpression
import org.neo4j.graphql.utils.ResolveTree


class AggregateResolver private constructor(
    schemaConfig: SchemaConfig,
    val implementingType: ImplementingType
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.EntityAugmentation {

        override fun augmentEntity(entity: Entity): List<AugmentedField> {
            if (entity !is ImplementingType
                || entity.annotations.query?.aggregate == false
                || (!ctx.schemaConfig.experimental && entity !is Node)
            ) {
                return emptyList()
            }
            val aggregationSelection = AggregationSelection.Augmentation.addAggregationSelectionType(entity, ctx)

            val coordinates =
                addQueryField(
                    entity.operations.rootTypeFieldNames.aggregate,
                    aggregationSelection.asRequiredType()
                ) { args ->
                    args += AggregateFieldArgs.Augmentation.getFieldArguments(entity, ctx)

                    if (entity is Node && entity.annotations.fulltext != null) {
                        FulltextInput.Augmentation.generateFulltextInput(entity, ctx)
                            ?.let {
                                args += inputValue(Constants.FULLTEXT, it.asType()) {
                                    description("Query a full-text index. Allows for the aggregation of results, but does not return the query score. Use the root full-text query fields if you require the score.".asDescription())
                                }
                            }
                    }
                }
            return AugmentedField(coordinates, AggregateResolver(ctx.schemaConfig, entity)).wrapList()
        }
    }

    private class AggregateFieldArgs(node: Node, args: Dict) {
        val where = args.nestedDict(Constants.WHERE)
            ?.let { WhereInput.NodeWhereInput(node, it) }

        object Augmentation : AugmentationBase {

            fun getFieldArguments(
                implementingType: ImplementingType,
                ctx: AugmentationContext
            ): List<InputValueDefinition> {
                val args = mutableListOf<InputValueDefinition>()

                WhereInput.Augmentation.generateWhereIT(implementingType, ctx)
                    ?.let { args += inputValue(Constants.WHERE, it.asType()) }

                return args
            }
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        if (implementingType !is Node) {
            TODO()
        }
        val resolveTree = ResolveTree.resolve(env)
            .parse({ AggregationSelection(implementingType, it) }, { AggregateFieldArgs(implementingType, it.args) })

        val selection = resolveTree.parsedSelection
        val arguments = resolveTree.parsedArguments
        val queryContext = env.queryContext()

        val dslNode = implementingType.asCypherNode(queryContext, variable)

// TODO harmonize with read
        var ongoingReading = TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
            .translateTopLevelMatch(
                implementingType,
                dslNode,
                null,
                arguments.where,
                AuthDirective.AuthOperation.READ
            )
            .let { reading ->
                AuthTranslator(
                    schemaConfig,
                    queryContext,
                    allow = AuthTranslator.AuthOptions(dslNode, implementingType)
                )
                    .createAuth(implementingType.auth, AuthDirective.AuthOperation.READ)
                    ?.let { reading.apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR) }
                    ?: reading
            }


        var authValidate: Condition? = null
        val projection = mutableListOf<Any>()

        projection.addAll(selection.count.project(Functions.count(dslNode)))

        selection.fieldSelection.forEach { (nodeField, fieldSelection) ->

            nodeField.auth?.let { auth ->
                AuthTranslator(
                    schemaConfig,
                    queryContext,
                    allow = AuthTranslator.AuthOptions(dslNode, implementingType)
                )
                    .createAuth(auth, AuthDirective.AuthOperation.READ)
                    ?.let { authValidate = authValidate and it }
            }

            val aggregateFields = fieldSelection
                .mapNotNull { it.fieldsByTypeName[nodeField.getAggregationSelectionLibraryTypeName()]?.values }
                .flatten()

            val property = dslNode.property(nodeField.dbPropertyName)

            val thisProjections = mutableListOf<Any>()
            aggregateFields.forEach { aggregateField ->
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
            projection.addAll(fieldSelection.project(Cypher.mapOf(*thisProjections.toTypedArray())))
        }

        authValidate?.let {
            ongoingReading = ongoingReading.apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
        }
        return ongoingReading
            .returning(Cypher.mapOf(*projection.toTypedArray()))
            .build()
    }
}

