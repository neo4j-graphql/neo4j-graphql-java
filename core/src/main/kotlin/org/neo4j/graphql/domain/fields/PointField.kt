package org.neo4j.graphql.domain.fields

import graphql.language.ListType
import graphql.language.NonNullType
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.predicates.FieldOperator
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition

class PointField(
    fieldName: String,
    typeMeta: TypeMeta,
    schemaConfig: SchemaConfig,
) : ScalarField(
    fieldName,
    typeMeta,
    schemaConfig,
), ConstrainableField, AuthableField, MutableField {

    override val predicates: Map<String, ScalarPredicateDefinition> = initPredicates()

    private fun initPredicates(): Map<String, ScalarPredicateDefinition> {
        val result = mutableMapOf<String, ScalarPredicateDefinition>()
            .add(FieldOperator.EQUAL)
            .add(FieldOperator.NOT_EQUAL)
        if (typeMeta.type.isList()) {
            result
                .addIncludesResolver(FieldOperator.INCLUDES)
                .addIncludesResolver(FieldOperator.NOT_INCLUDES)

        } else {
            result
                .addDistanceResolver(FieldOperator.LT)
                .addDistanceResolver(FieldOperator.LTE)
                .addDistanceResolver(FieldOperator.GT)
                .addDistanceResolver(FieldOperator.GTE)
                .addDistanceResolver(FieldOperator.EQUAL, "DISTANCE")
                .addInResolver(FieldOperator.IN)
                .addInResolver(FieldOperator.NOT_IN)
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
                    Functions.distance(property, Functions.point(parameter.property("point"))),
                    parameter.property("distance")
                )
            },
            type = when (typeMeta.type.name()) {
                Constants.POINT_TYPE -> Constants.Types.PointDistance
                Constants.CARTESIAN_POINT_TYPE -> Constants.Types.CartesianPointDistance
                else -> error("unsupported point type " + typeMeta.type.name())
            }
        )
    }

    private fun MutableMap<String, ScalarPredicateDefinition>.addInResolver(op: FieldOperator): MutableMap<String, ScalarPredicateDefinition> {
        return this.add(
            op.suffix, { property, parameter ->
                val p = Cypher.name("p")
                val paramPointArray = Cypher.listWith(p).`in`(parameter).returning(Functions.point(p))
                op.conditionCreator(property, paramPointArray)
            }, type = ListType(NonNullType(typeMeta.whereType))
        )
    }

    private fun MutableMap<String, ScalarPredicateDefinition>.addIncludesResolver(op: FieldOperator): MutableMap<String, ScalarPredicateDefinition> {
        return this.add(
            op.suffix, { property, parameter ->
                val paramPoint = Functions.point(parameter)
                op.conditionCreator(paramPoint, property)
            }, type = typeMeta.whereType.inner()
        )
    }
}
