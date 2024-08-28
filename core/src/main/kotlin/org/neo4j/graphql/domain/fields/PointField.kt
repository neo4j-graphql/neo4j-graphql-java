package org.neo4j.graphql.domain.fields

import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.TypeName
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.Constants
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.predicates.FieldOperator
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition
import org.neo4j.graphql.inner
import org.neo4j.graphql.isList
import org.neo4j.graphql.schema.model.outputs.point.BasePointSelection
import org.neo4j.graphql.schema.model.outputs.point.CartesianPointSelection
import org.neo4j.graphql.schema.model.outputs.point.PointSelection
import org.neo4j.graphql.utils.IResolveTree

class PointField(
    fieldName: String,
    typeMeta: TypeMeta,
    val type: Type,
    annotations: FieldAnnotations,
    schemaConfig: SchemaConfig
) : ScalarField(
    fieldName,
    typeMeta,
    annotations,
    schemaConfig,
), ConstrainableField, AuthableField, MutableField {

    override val predicateDefinitions: Map<String, ScalarPredicateDefinition> = initPredicates()

    private fun initPredicates(): Map<String, ScalarPredicateDefinition> {
        val result = mutableMapOf<String, ScalarPredicateDefinition>()
            .add(FieldOperator.EQUAL)
        if (typeMeta.type.isList()) {
            result.addIncludesResolver(FieldOperator.INCLUDES)
        } else {
            result
                .addDistanceResolver(FieldOperator.LT)
                .addDistanceResolver(FieldOperator.LTE)
                .addDistanceResolver(FieldOperator.GT)
                .addDistanceResolver(FieldOperator.GTE)
                .addDistanceResolver(FieldOperator.EQUAL, "DISTANCE")
                .addInResolver(FieldOperator.IN)
        }

        return result
    }

    private fun MutableMap<String, ScalarPredicateDefinition>.addDistanceResolver(
        op: FieldOperator,
        suffix: String = op.suffix
    ): MutableMap<String, ScalarPredicateDefinition> {
        return this.add(
            suffix,
            { property, parameter ->
                op.conditionCreator(
                    Cypher.distance(property, Cypher.point(parameter.property("point"))),
                    parameter.property("distance")
                )
            },
            { _, _ -> TODO() },
            type = type.inputType
        )
    }

    private fun MutableMap<String, ScalarPredicateDefinition>.addInResolver(op: FieldOperator): MutableMap<String, ScalarPredicateDefinition> {
        return this.add(
            op.suffix,
            { property, parameter ->
                val p = Cypher.name("p")
                val paramPointArray = Cypher.listWith(p).`in`(parameter).returning(Cypher.point(p))
                op.conditionCreator(property, paramPointArray)
            },
            { _, _ -> TODO() },
            type = ListType(NonNullType(typeMeta.whereType))
        )
    }

    private fun MutableMap<String, ScalarPredicateDefinition>.addIncludesResolver(op: FieldOperator): MutableMap<String, ScalarPredicateDefinition> {
        return this.add(
            op.suffix,
            { property, parameter ->
                val paramPoint = Cypher.point(parameter)
                op.conditionCreator(property, paramPoint)
            },
            { _, _ -> TODO() },
            type = typeMeta.whereType.inner()
        )
    }

    override fun convertInputToCypher(input: Expression): Expression = if (typeMeta.type.isList()) {
        val point = Cypher.name("p")
        Cypher.listWith(point).`in`(input).returning(Cypher.point(point))
    } else {
        Cypher.point(input)
    }

    fun parseSelection(rt: IResolveTree) = type.selectionFactory(rt)

    enum class Type(
        internal val inputType: TypeName,
        internal val selectionFactory: (IResolveTree) -> BasePointSelection
    ) {
        GEOGRAPHIC(Constants.Types.PointDistance, ::PointSelection),
        CARTESIAN(Constants.Types.CartesianPointDistance, ::CartesianPointSelection)
    }
}
