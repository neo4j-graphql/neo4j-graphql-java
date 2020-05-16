package org.neo4j.graphql

import graphql.Scalars
import graphql.schema.*
import org.neo4j.graphql.Predicate.Companion.resolvePredicate
import org.neo4j.graphql.handler.projection.ProjectionBase
import org.neo4j.opencypherdsl.Condition
import org.neo4j.opencypherdsl.Expression
import org.slf4j.LoggerFactory

interface Predicate {
    fun toExpression(variable: String): Cypher

    companion object {
        fun resolvePredicate(name: String, value: Any?, type: GraphQLFieldsContainer): Predicate {
            for (definedField in type.fieldDefinitions) {
                if (definedField.isRelationship()) {
                    val op = RelationOperator.values().find { name == definedField.name + it.suffix }
                    if (op != null) {
                        return when (value) {
                            is Map<*, *> -> RelationPredicate(definedField.name, op, value, type)
                            null -> IsNullPredicate(definedField.name, op, type)
                            else -> throw IllegalArgumentException("Input for ${definedField.name} must be an filter-InputType")
                        }
                    }
                } else {
                    val op = FieldOperator.resolve(name, definedField.name, value)
                    if (op != null) {
                        return ExpressionPredicate(definedField.name, op, value, definedField)
                    }
                }
            }
            throw IllegalArgumentException("Queried field $name could not be resolved")
        }

        private fun isParam(value: String) = value.startsWith("{") && value.endsWith("}") || value.startsWith("\$")

        @Suppress("unused", "SimplifiableCallChain")
        private fun formatAnyValueCypher(value: Any?): String =
                when (value) {
                    null -> "null"
                    is String -> if (isParam(value)) value else "\"$value\""
                    is Map<*, *> -> "{" + value.map { it.key.toString() + ":" + formatAnyValueCypher(it.value) }.joinToString(",") + "}"
                    is Iterable<*> -> "[" + value.map { formatAnyValueCypher(it) }.joinToString(",") + "]"
                    else -> value.toString()

                }
    }
}

fun toExpression(name: String, value: Any?, type: GraphQLFieldsContainer): Predicate =
        if (name == "AND" || name == "OR")
            when (value) {
                is Iterable<*> -> CompoundPredicate(value.map { toExpression("AND", it, type) }, name)
                is Map<*, *> -> CompoundPredicate(value.map { (k, v) -> toExpression(k.toString(), v, type) }, name)
                else -> throw IllegalArgumentException("Unexpected value for filter: $value")
            }
        else {
            resolvePredicate(name, value, type)
        }

data class CompoundPredicate(val parts: List<Predicate>, val op: String = "AND") : Predicate {
    override fun toExpression(variable: String): Cypher =
            parts.map { it.toExpression(variable) }
                .let { expressions ->
                    Cypher(
                            expressions.map { it.query }.joinNonEmpty(" $op ", "(", ")"),
                            expressions.fold(emptyMap()) { res, exp -> res + exp.params }
                    )
                }
}

data class IsNullPredicate(val fieldName: String, val op: RelationOperator, val type: GraphQLFieldsContainer) : Predicate {
    override fun toExpression(variable: String): Cypher {
        val rel = type.relationshipFor(fieldName) ?: throw IllegalArgumentException("Not a relation")
        val (left, right) = rel.arrows
        val not = if (op == RelationOperator.NOT) "" else "NOT "
        return Cypher("$not($variable)$left-[:${rel.relType}]-$right()")
    }
}

data class ExpressionPredicate(val name: String, val op: FieldOperator, val value: Any?, val fieldDefinition: GraphQLFieldDefinition) : Predicate {
    val not = if (op.not) "NOT " else ""
    override fun toExpression(variable: String): Cypher {
        val paramName: String = ProjectionBase.FILTER + paramName(variable, name, value).capitalize() + "_" + op.name
        val query = if (fieldDefinition.isNativeId()) {
            if (op.list) {
                "${not}ID($variable) ${op.op} [id IN \$$paramName | toInteger(id)]"
            } else {
                "${not}ID($variable) ${op.op} toInteger(\$$paramName)"
            }
        } else {
            "$not$variable.${name.quote()} ${op.op} \$$paramName"
        }
        return Cypher(query, mapOf(paramName to value))
    }
}


data class RelationPredicate(val fieldName: String, val op: RelationOperator, val value: Map<*, *>, val type: GraphQLFieldsContainer) : Predicate {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(RelationPredicate::class.java)
    }

    val not = if (op == RelationOperator.NOT) "NOT" else ""
    // (type)-[:TYPE]->(related) | pred] = 0/1/ > 0 | =
    // ALL/ANY/NONE/SINGLE(p in (type)-[:TYPE]->() WHERE pred(last(nodes(p)))
    // ALL/ANY/NONE/SINGLE(x IN [(type)-[:TYPE]->(o) | pred(o)] WHERE x)

    override fun toExpression(variable: String): Cypher {
        val prefix = when (op) {
            RelationOperator.EQ_OR_NOT_EXISTS -> "ALL"
            RelationOperator.NOT -> "ALL" // bc of not
            else -> op.op
        }
        if (type.getFieldDefinition(fieldName).isList()) {
            if (op == RelationOperator.EQ_OR_NOT_EXISTS) {
                LOGGER.info("$fieldName on type ${type.name} was used for filtering, consider using ${fieldName}${RelationOperator.EVERY.suffix} instead")
            }
        } else {
            @Suppress("NON_EXHAUSTIVE_WHEN")
            when (op) {
                RelationOperator.SINGLE -> LOGGER.warn("Using $fieldName${RelationOperator.SINGLE.suffix} on type ${type.name} is deprecated, use ${fieldName} directly")
                RelationOperator.SOME -> LOGGER.warn("Using $fieldName${RelationOperator.SOME.suffix} on type ${type.name} is deprecated, use ${fieldName} directly")
                RelationOperator.NONE -> LOGGER.warn("Using $fieldName${RelationOperator.NONE.suffix} on type ${type.name} is deprecated, use ${fieldName}${RelationOperator.NOT.suffix} instead")
            }
        }
        val rel = type.relationshipFor(fieldName) ?: throw IllegalArgumentException("Not a relation")
        val (left, right) = rel.arrows
        val other = variable + "_" + rel.typeName
        val cond = other + "_Cond"
        val (pred, params) = CompoundPredicate(value.map { resolvePredicate(it.key.toString(), it.value, rel.type) }).toExpression(other)
        return Cypher("$not $prefix($cond IN [($variable)$left-[:${rel.relType.quote()}]-$right($other) | $pred] WHERE $cond)", params)
    }
}

enum class FieldOperator(val suffix: String, val op: String, val conditionCreator: (Expression, Expression) -> Condition, val not: Boolean = false) {
    EQ("", "=", { lhs, rhs -> lhs.isEqualTo(rhs) }),
    IS_NULL("", "", { lhs, _ -> lhs.isNull }),
    IS_NOT_NULL("_not", "", { lhs, _ -> lhs.isNotNull }, true),
    NEQ("_not", "=", { lhs, rhs -> lhs.isNotEqualTo(rhs) }, true),
    GTE("_gte", ">=", { lhs, rhs -> lhs.gte(rhs) }),
    GT("_gt", ">", { lhs, rhs -> lhs.gt(rhs) }),
    LTE("_lte", "<=", { lhs, rhs -> lhs.lte(rhs) }),
    LT("_lt", "<", { lhs, rhs -> lhs.lt(rhs) }),

    NIN("_not_in", "IN", { lhs, rhs -> lhs.`in`(rhs).not() }, true),
    IN("_in", "IN", { lhs, rhs -> lhs.`in`(rhs) }),
    NC("_not_contains", "CONTAINS", { lhs, rhs -> lhs.contains(rhs).not() }, true),
    NSW("_not_starts_with", "STARTS WITH", { lhs, rhs -> lhs.startsWith(rhs).not() }, true),
    NEW("_not_ends_with", "ENDS WITH", { lhs, rhs -> lhs.endsWith(rhs).not() }, true),
    C("_contains", "CONTAINS", { lhs, rhs -> lhs.contains(rhs) }),
    SW("_starts_with", "STARTS WITH", { lhs, rhs -> lhs.startsWith(rhs) }),
    EW("_ends_with", "ENDS WITH", { lhs, rhs -> lhs.endsWith(rhs) });

    val list = op == "IN"

    companion object {

        fun resolve(queriedField: String, field: String, value: Any?): FieldOperator? {
            if (value == null) {
                return listOf(IS_NULL, IS_NOT_NULL).find { queriedField == field + it.suffix } ?: return null
            }
            val fieldOperator = enumValues<FieldOperator>()
                .filterNot { it == IS_NULL || it == IS_NOT_NULL }
                .find { queriedField == field + it.suffix } ?: return null
            val op = fieldOperator
            return op
        }

        fun forType(type: GraphQLType): List<FieldOperator> =
                when {
                    type == Scalars.GraphQLBoolean -> listOf(EQ, NEQ)
                    type.isNeo4jType() -> listOf(EQ, NEQ, IN, NIN)
                    type is GraphQLFieldsContainer || type is GraphQLInputObjectType -> throw IllegalArgumentException("This operators are not for relations, use the RelationOperator instead")
                    type is GraphQLEnumType -> listOf(EQ, NEQ, IN, NIN)
                    // todo list types
                    !type.isScalar() -> listOf(EQ, NEQ, IN, NIN)
                    else -> listOf(EQ, NEQ, IN, NIN, LT, LTE, GT, GTE) +
                            if (type.name == "String" || type.name == "ID") listOf(C, NC, SW, NSW, EW, NEW) else emptyList()
                }

    }

    fun fieldName(fieldName: String) = fieldName + suffix
}

enum class RelationOperator(val suffix: String, val op: String) {
    SOME("_some", "ANY"),

    EVERY("_every", "ALL"),

    SINGLE("_single", "SINGLE"),
    NONE("_none", "NONE"),

    // `eq` if queried with an object, `not exists` if  queried with null
    EQ_OR_NOT_EXISTS("", ""),
    NOT("_not", "");

    fun fieldName(fieldName: String) = fieldName + suffix
}