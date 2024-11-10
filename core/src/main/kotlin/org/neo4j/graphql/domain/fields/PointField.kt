package org.neo4j.graphql.domain.fields

import graphql.language.Type
import graphql.language.TypeName
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.*
import org.neo4j.graphql.Constants.CARTESIAN_POINT_INPUT_TYPE
import org.neo4j.graphql.Constants.POINT_INPUT_TYPE
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.predicates.FieldOperator
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition
import org.neo4j.graphql.schema.model.outputs.point.BasePointSelection
import org.neo4j.graphql.schema.model.outputs.point.CartesianPointSelection
import org.neo4j.graphql.schema.model.outputs.point.PointSelection
import org.neo4j.graphql.utils.IResolveTree

class PointField(
    fieldName: String,
    type: Type<*>,
    private val coordinateType: CoordinateType,
    annotations: FieldAnnotations,
    schemaConfig: SchemaConfig
) : ScalarField(
    fieldName,
    type,
    annotations,
    schemaConfig,
) {

    override val predicateDefinitions: Map<String, ScalarPredicateDefinition> = initPredicates()
    override val whereType
        get() = when (coordinateType) {
            CoordinateType.GEOGRAPHIC -> POINT_INPUT_TYPE.asType()
            CoordinateType.CARTESIAN -> CARTESIAN_POINT_INPUT_TYPE.asType()
        }

    private fun initPredicates(): Map<String, ScalarPredicateDefinition> {
        val result = mutableMapOf<String, ScalarPredicateDefinition>()
            .add(FieldOperator.EQUAL)
        if (isList()) {
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
            type = coordinateType.inputType
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
            type = whereType.NonNull.List
        )
    }

    private fun MutableMap<String, ScalarPredicateDefinition>.addIncludesResolver(op: FieldOperator): MutableMap<String, ScalarPredicateDefinition> {
        return this.add(
            op.suffix,
            { property, parameter ->
                val paramPoint = Cypher.point(parameter)
                op.conditionCreator(property, paramPoint)
            },
            type = whereType.inner()
        )
    }

    override fun convertInputToCypher(input: Expression): Expression = if (isList()) {
        val point = Cypher.name("p")
        Cypher.listWith(point).`in`(input).returning(Cypher.point(point))
    } else {
        Cypher.point(input)
    }

    fun parseSelection(rt: IResolveTree) = coordinateType.selectionFactory(rt)

    enum class CoordinateType(
        internal val inputType: TypeName,
        internal val selectionFactory: (IResolveTree) -> BasePointSelection<*>
    ) {
        GEOGRAPHIC(Constants.Types.PointDistance, PointSelection::parse),
        CARTESIAN(Constants.Types.CartesianPointDistance, CartesianPointSelection::parse)
    }
}
