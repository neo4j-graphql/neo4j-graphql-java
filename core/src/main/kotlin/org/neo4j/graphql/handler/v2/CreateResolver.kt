package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.InputValueDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.ArgumentsAugmentation
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.create.CreateFieldInput
import org.neo4j.graphql.schema.model.inputs.create.CreateInput
import org.neo4j.graphql.schema.model.inputs.create.RelationFieldInput
import org.neo4j.graphql.schema.model.outputs.NodeSelection
import org.neo4j.graphql.translate.*
import org.neo4j.graphql.utils.IResolveTree
import org.neo4j.graphql.utils.ObjectFieldSelection
import org.neo4j.graphql.utils.ResolveTree

class CreateResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.NodeAugmentation {

        override fun augmentNode(node: Node): List<AugmentedField> {
            if (node.annotations.mutation?.create == false) {
                return emptyList()
            }

            val arguments = CreateInputArguments.Augmentation(node, ctx).getAugmentedArguments()
            if (arguments.isEmpty()) {
                return emptyList()
            }

            val responseType = CreateMutationSelection.Augmentation.getCreateResponse(node, ctx)

            val coordinates = addMutationField(
                node.namings.rootTypeFieldNames.create,
                responseType.asRequiredType(),
                arguments
            )
            return AugmentedField(coordinates, CreateResolver(ctx.schemaConfig, node)).wrapList()
        }
    }

    private class CreateMutationSelection(node: Node, selection: IResolveTree) {

        val info = selection.getSingleFieldOfType(
            node.namings.mutationResponseTypeNames.create,
            Constants.INFO_FIELD
        )

        val node = selection.getSingleFieldOfType(node.namings.mutationResponseTypeNames.create, node.plural)

        object Augmentation : AugmentationBase {
            fun getCreateResponse(node: Node, ctx: AugmentationContext) =
                ctx.getOrCreateObjectType(node.namings.mutationResponseTypeNames.create) { fields, _ ->
                    fields += field(Constants.INFO_FIELD, NonNullType(Constants.Types.CreateInfo))
                    NodeSelection.Augmentation.generateNodeSelection(node, ctx)
                        ?.let { fields += field(node.plural, NonNullType(ListType(it.asRequiredType()))) }
                }
                    ?: throw IllegalStateException("Expected at least the info field")
        }
    }

    private class CreateInputArguments(node: Node, args: Dict) {

        val input = args.nestedDictList(Constants.INPUT_FIELD)
            .map { CreateInput.create(node, it) }
            .takeIf { it.isNotEmpty() }

        class Augmentation(val node: Node, val ctx: AugmentationContext) : ArgumentsAugmentation {

            override fun augmentArguments(args: MutableList<InputValueDefinition>) {
                CreateInput.Augmentation
                    .generateContainerCreateInputIT(node, ctx)
                    ?.let { inputType ->
                        args += inputValue(Constants.INPUT_FIELD, NonNullType(ListType(inputType.asRequiredType())))
                    }

            }
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val queryContext = env.queryContext()

        val request = ResolveTree.resolve(env).parse(
            { CreateMutationSelection(node, it) },
            { CreateInputArguments(node, it.args) }
        )
        try {
            return unwindCreate(variable, field, env, request, queryContext)
        } catch (e: UnsupportedUnwindOptimization) {
            // ignore
        }


        var queries: StatementBuilder.OngoingReadingWithoutWhere? = null
        val nodes = mutableListOf<org.neo4j.cypherdsl.core.Node>()
        request.parsedArguments.input?.forEachIndexed { index, value ->
            val dslNode = node.asCypherNode(queryContext).named("this$index")

            val statement = (
                    CreateTranslator(schemaConfig, queryContext)
                        .createCreateAndParams(
                            node,
                            dslNode,
                            value,
                            listOf(dslNode.requiredSymbolicName)
                        ) as ExposesReturning
                    ).returning(dslNode)
                .build()

            queries = queries?.call(statement) ?: Cypher.call(statement)
            nodes += dslNode
        }
        if (queries == null) {
            throw IllegalArgumentException("nothing to create for ${env.fieldDefinition}")
        }

        val (dslNode, result) = if (nodes.size > 1) {
            val node = Cypher.anyNode().named("this")
            node to queries!!
                .unwind(Cypher.listOf(*nodes.map { it.requiredSymbolicName }.toTypedArray()))
                .`as`(node.requiredSymbolicName)
        } else {
            nodes[0] to queries!!
        }


        val projection = ProjectionTranslator()
            .createProjectionAndParams(
                node,
                dslNode,
                request.parsedSelection.node,
                chainStr = null,
                schemaConfig,
                queryContext
            )

        return result
            .withSubQueries(projection.allSubQueries)
            .returning(Cypher.listOf(dslNode.project(projection.projection)).`as`("data"))
            .build()

    }


    class UnsupportedUnwindOptimization(msg: String) : RuntimeException(msg)

    private fun unwindCreate(
        variable: String,
        field: Field,
        env: DataFetchingEnvironment,
        request: ObjectFieldSelection<CreateMutationSelection, CreateInputArguments>,
        queryContext: QueryContext,
    ): Statement {
//        if (env.queryContext().subscriptionsEnabled) {
//            throw new UnsupportedUnwindOptimization("Unwind create optimisation does not yet support subscriptions");
//        }

        val mergedCreateInput = MergedCreateInput(request.parsedArguments.input ?: emptyList())

        val prefix = ChainString(schemaConfig, "create", "this")

        val param = queryContext.getNextParam(prefix.extend("param"), env.arguments[Constants.INPUT_FIELD])
        val unwindVar = Cypher.name("input")


        val currentNode = node.asCypherNode(queryContext).named(queryContext.getNextVariable(prefix))

        val create = createSubquery(currentNode, mergedCreateInput, queryContext, unwindVar, prefix)

        val projection = ProjectionTranslator()
            .createProjectionAndParams(
                node,
                currentNode,
                request.parsedSelection.node,
                chainStr = null,
                schemaConfig,
                queryContext
            )

        return Cypher.unwind(param).`as`(unwindVar)
            .call(create)
            .withSubQueries(projection.allSubQueries)
            .returning(Functions.collect(currentNode.project(projection.projection)).`as`("data"))
            .build()
    }

    private fun createSubquery(
        currentNode: org.neo4j.cypherdsl.core.Node,
        input: MergedCreateInput,
        queryContext: QueryContext,
        unwindVar: SymbolicName,
        prefix: ChainString
    ): Statement {


        val nestedCalls = input.create.map { (field, connectionInput) ->
            nestedCreateSubquery(field, connectionInput, currentNode, unwindVar, queryContext, prefix)
        }
        val setExpressions = createSetPropertiesOnCreate(
            currentNode,
            input.properties.map { it to unwindVar.property(it.fieldName) },
            node
        )

        val authConditions = createAuthCondition(
            node,
            input.properties,
            currentNode,
            unwindVar,
            queryContext
        )

        val relationshipValidationClause = RelationshipValidationTranslator.createRelationshipValidations(
            node,
            currentNode,
            queryContext,
            schemaConfig
        )

        return Cypher
            .with(unwindVar)
            .create(currentNode)
            .let {
                when {
                    setExpressions.isNullOrEmpty() -> it
                    else -> it.set(*setExpressions.toTypedArray())
                }
            }
            .let {
                if (nestedCalls.isEmpty()) {
                    it
                } else {
                    it.with(currentNode, unwindVar)
                        .withSubQueries(nestedCalls)
                }
            }

            .let { reading ->
                authConditions?.let {
                    reading
                        .with(Cypher.asterisk())
                        .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
                } ?: reading
            }
            .let {
                if (relationshipValidationClause.isNotEmpty()) {
                    it.with(currentNode)
                        .withSubQueries(relationshipValidationClause)
                } else {
                    it
                }
            }
            .returning(currentNode)
            .build()
    }

    private fun nestedCreateSubquery(
        field: RelationField,
        connectionInput: MergedConnectionCreateInput,
        parentVar: org.neo4j.cypherdsl.core.Node,
        unwindVar: SymbolicName,
        queryContext: QueryContext,
        prefix: ChainString
    ): Statement {

        val createUnwindVar = Cypher.name("nestedCreate")
        val nodeVar = Cypher.name("nodeInput")
        val edgeVar = Cypher.name("edgeInput")

        val node = field.node ?: error("expected node relationship")
        val currentNode = node.asCypherNode(queryContext)
            .named(queryContext.getNextVariable(prefix))


        val setNodeExpressions = createSetPropertiesOnCreate(
            currentNode,
            connectionInput.nodeInput?.properties?.map { it to nodeVar.property(it.fieldName) },
            node
        )

        val rel = field.createDslRelation(parentVar, currentNode, "edge")

        val setEdgeExpressions = createSetPropertiesOnCreate(
            rel,
            connectionInput.edgeProperties?.map { it to edgeVar.property(it.fieldName) },
            field.properties
        )

        val nestedCalls = connectionInput.nodeInput
            ?.create
            ?.map { (nestedField, nestedInput) ->
                nestedCreateSubquery(nestedField, nestedInput, currentNode, nodeVar, queryContext, prefix)
            }

        val authConditions = createAuthCondition(
            node,
            connectionInput.nodeInput?.properties,
            currentNode,
            nodeVar,
            queryContext
        )

        val relationshipValidationClause = RelationshipValidationTranslator.createRelationshipValidations(
            node,
            currentNode,
            queryContext,
            schemaConfig
        )

        return Cypher.with(parentVar as IdentifiableElement, unwindVar)
            .unwind(unwindVar.property(field.fieldName).property(Constants.CREATE_FIELD)).`as`(createUnwindVar)
            .with(
                createUnwindVar.property(Constants.NODE_FIELD).`as`(nodeVar),
                createUnwindVar.property(Constants.EDGE_FIELD).`as`(edgeVar),
                parentVar
            )
            .create(currentNode)
            .let {
                when {
                    setNodeExpressions.isNullOrEmpty() -> it
                    else -> it.set(*setNodeExpressions.toTypedArray())
                }
            }
            .merge(rel)
            .let {
                when {
                    setEdgeExpressions.isNullOrEmpty() -> it
                    else -> it.set(*setEdgeExpressions.toTypedArray())
                }
            }
            .let {
                if (nestedCalls.isNullOrEmpty()) {
                    it
                } else {
                    it.with(Cypher.asterisk()).withSubQueries(nestedCalls)
                }
            }
            .let { reading ->
                authConditions?.let {
                    reading
                        .with(Cypher.asterisk())
                        .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
                } ?: reading
            }
            .let {
                if (relationshipValidationClause.isNotEmpty()) {
                    it.with(currentNode)
                        .withSubQueries(relationshipValidationClause)
                } else {
                    it
                }
            }
            .returning(Functions.collect(Cypher.literalNull()).`as`("_"))
            .build()
    }

    private fun createAuthCondition(
        node: Node,
        scalarFields: Set<ScalarField>?,
        dslNode: org.neo4j.cypherdsl.core.Node,
        nodeParam: SymbolicName,
        queryContext: QueryContext
    ): Condition? {
        var authConditions: Condition? = null

        scalarFields?.forEach { field ->
            if (field.auth != null) {
                AuthTranslator(schemaConfig, queryContext, bind = AuthTranslator.AuthOptions(dslNode, node))
                    .createAuth(field.auth, AuthDirective.AuthOperation.CREATE)
                    ?.let { authConditions = authConditions and (nodeParam.property(field.fieldName).isNull or it) }
            }
        }

        AuthTranslator(schemaConfig, queryContext, bind = AuthTranslator.AuthOptions(dslNode, node))
            .createAuth(node.auth, AuthDirective.AuthOperation.CREATE)
            ?.let { authConditions = authConditions and it }
        return authConditions
    }

    class MergedCreateInput(inputs: List<CreateInput>) {

        val properties = inputs
            .mapNotNull { it.properties?.keys }
            .flatten()
            .toSet()

        val create: Map<RelationField, MergedConnectionCreateInput>

        init {
            val create = mutableMapOf<RelationField, MergedConnectionCreateInput>()

            inputs.flatMap { it.relations.entries }
                .groupBy(
                    { it.key },
                    {
                        it.value as? CreateFieldInput.NodeFieldInput
                            ?: throw UnsupportedUnwindOptimization("Not supported operation: Interface or Union")
                    })
                .forEach { (field, inputs) ->
                    val nestedConnectInput = inputs.mapNotNull { it.connect }.flatten()
                    if (nestedConnectInput.isNotEmpty()) {
                        throw UnsupportedUnwindOptimization("Not supported operation: connect");
                    }

                    val nestedConnectOrCreateInput = inputs.mapNotNull { it.connectOrCreate }.flatten()
                    if (nestedConnectOrCreateInput.isNotEmpty()) {
                        throw UnsupportedUnwindOptimization("Not supported operation: connectOrCreate");
                    }

                    val nestedCreateInput = inputs.mapNotNull { it.create }.flatten()
                    if (nestedCreateInput.isNotEmpty()) {
                        create[field] = MergedConnectionCreateInput(field, nestedCreateInput)
                    }
                }

            this.create = create
        }
    }


    class MergedConnectionCreateInput(
        val field: RelationField,
        inputs: List<RelationFieldInput.NodeCreateCreateFieldInput>
    ) {

        val nodeInput = inputs
            .mapNotNull { it.node }
            .takeIf { it.isNotEmpty() }
            ?.let { MergedCreateInput(it) }

        val edgeProperties = inputs
            .mapNotNull { it.edge?.keys }
            .flatten()
            .toSet()
            .takeIf { it.isNotEmpty() }
    }

}

