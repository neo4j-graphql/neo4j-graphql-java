package org.neo4j.graphql

import graphql.Scalars
import graphql.language.NullValue
import graphql.language.ObjectValue
import graphql.language.Value
import graphql.schema.*
import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.Functions.point
import org.neo4j.graphql.Predicate.Companion.resolvePredicate
import org.neo4j.graphql.handler.projection.ProjectionBase
import org.slf4j.LoggerFactory

typealias CypherDSL = org.neo4j.cypherdsl.core.Cypher

interface Predicate {
    fun toExpression(variable: String): Cypher

    companion object {
        fun resolvePredicate(name: String, value: Any?, type: GraphQLFieldsContainer): Predicate {
            for (definedField in type.fieldDefinitions) {
                return if (definedField.isRelationship()) {
                    val op = RelationOperator.values().find { name == definedField.name + it.suffix } ?: continue
                    when (value) {
                        is Map<*, *> -> RelationPredicate(definedField.name, op, value, type)
                        null -> IsNullRelationPredicate(definedField.name, op, type)
                        else -> throw IllegalArgumentException("Input for ${definedField.name} must be an filter-InputType")
                    }
                } else {
                    val op = FieldOperator.resolve(name, definedField, value) ?: continue
                    when {
                        definedField.type.isNeo4jType() -> resolveNeo4jTypePredicate(type, definedField, op, value)
                        else -> ExpressionPredicate(definedField.name, op, value, definedField)
                    }
                }
            }
            throw IllegalArgumentException("Queried field $name could not be resolved")
        }

        private fun resolveNeo4jTypePredicate(type: GraphQLFieldsContainer, field: GraphQLFieldDefinition, op: FieldOperator, value: Any?): Predicate {
            if (op.distance) {
                return DistancePredicate(field.name, op, value, field)
            }
            return when (value) {
                is Map<*, *> -> CompoundPredicate(value.entries.map { (key, value) ->
                    ExpressionPredicate(field.name, op, value, field, ".$key")
                })
                null -> IsNullFieldPredicate(field.name, op, type)
                else -> throw IllegalArgumentException("Input for ${field.name} must be an filter-InputType")
            }
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

data class IsNullRelationPredicate(val fieldName: String, val op: RelationOperator, val type: GraphQLFieldsContainer) : Predicate {
    override fun toExpression(variable: String): Cypher {
        val rel = type.relationshipFor(fieldName) ?: throw IllegalArgumentException("Not a relation")
        val (left, right) = rel.arrows
        val not = if (op == RelationOperator.NOT) "" else "NOT "
        return Cypher("$not($variable)$left-[:${rel.relType}]-$right()")
    }
}

data class IsNullFieldPredicate(val fieldName: String, val op: FieldOperator, val type: GraphQLFieldsContainer) : Predicate {
    override fun toExpression(variable: String): Cypher {
        val check = if (op == FieldOperator.IS_NOT_NULL) "IS NOT NULL" else "IS NULL"
        return Cypher("$variable.$fieldName $check")
    }
}

data class ExpressionPredicate(
        val name: String,
        val op: FieldOperator,
        val value: Any?,
        val fieldDefinition: GraphQLFieldDefinition,
        val nestedField: String = ""
) : Predicate {
    val not = if (op.not) "NOT " else ""
    override fun toExpression(variable: String): Cypher {
        val paramName: String = ProjectionBase.FILTER + paramName(variable, name, value).capitalize() + "_" + op.name + nestedField.replace('.', '_')
        val query = if (fieldDefinition.isNativeId()) {
            if (op.list) {
                "${not}ID($variable) ${op.op} [id IN \$$paramName | toInteger(id)]"
            } else {
                "${not}ID($variable) ${op.op} toInteger(\$$paramName)"
            }
        } else {
            "$not$variable.${name.quote()}${nestedField} ${op.op} \$$paramName"
        }
        return Cypher(query, mapOf(paramName to value))
    }
}

data class DistancePredicate(val name: String, val op: FieldOperator, val value: Any?, val fieldDefinition: GraphQLFieldDefinition) : Predicate {
    override fun toExpression(variable: String): Cypher {
        val paramName: String = ProjectionBase.FILTER + paramName(variable, name, value).capitalize() + "_" + op.name
        val query = "distance($variable.${name.quote()}, point(\$$paramName.point)) ${op.op} \$$paramName.distance"
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
        if (type.getFieldDefinition(fieldName).type.isList()) {
            if (op == RelationOperator.EQ_OR_NOT_EXISTS) {
                LOGGER.info("$fieldName on type ${type.name} was used for filtering, consider using ${fieldName}${RelationOperator.EVERY.suffix} instead")
            }
        } else {
            when (op) {
                RelationOperator.SINGLE -> LOGGER.warn("Using $fieldName${RelationOperator.SINGLE.suffix} on type ${type.name} is deprecated, use $fieldName directly")
                RelationOperator.SOME -> LOGGER.warn("Using $fieldName${RelationOperator.SOME.suffix} on type ${type.name} is deprecated, use $fieldName directly")
                RelationOperator.NONE -> LOGGER.warn("Using $fieldName${RelationOperator.NONE.suffix} on type ${type.name} is deprecated, use ${fieldName}${RelationOperator.NOT.suffix} instead")
                else -> {
                    // nothing to log
                }
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

enum class FieldOperator(
        val suffix: String,
        val op: String,
        private val conditionCreator: (Expression, Parameter) -> Condition,
        val not: Boolean = false,
        val requireParam: Boolean = true,
        val distance: Boolean = false
) {
    EQ("", "=", { lhs, rhs -> lhs.isEqualTo(rhs) }),
    IS_NULL("", "", { lhs, _ -> lhs.isNull }, requireParam = false),
    IS_NOT_NULL("_not", "", { lhs, _ -> lhs.isNotNull }, true, requireParam = false),
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
    EW("_ends_with", "ENDS WITH", { lhs, rhs -> lhs.endsWith(rhs) }),

    DISTANCE(NEO4j_POINT_DISTANCE_FILTER_SUFFIX, "=", { lhs, rhs -> distanceOp(lhs, rhs, EQ) }, distance = true),
    DISTANCE_LT(NEO4j_POINT_DISTANCE_FILTER_SUFFIX + "_lt", "<", { lhs, rhs -> distanceOp(lhs, rhs, LT) }, distance = true),
    DISTANCE_LTE(NEO4j_POINT_DISTANCE_FILTER_SUFFIX + "_lte", "<=", { lhs, rhs -> distanceOp(lhs, rhs, LTE) }, distance = true),
    DISTANCE_GT(NEO4j_POINT_DISTANCE_FILTER_SUFFIX + "_gt", ">", { lhs, rhs -> distanceOp(lhs, rhs, GT) }, distance = true),
    DISTANCE_GTE(NEO4j_POINT_DISTANCE_FILTER_SUFFIX + "_gte", ">=", { lhs, rhs -> distanceOp(lhs, rhs, GTE) }, distance = true);

    val list = op == "IN"

    fun resolveCondition(variablePrefix: String, queriedField: String, propertyContainer: PropertyContainer, field: GraphQLFieldDefinition, value: Any): Pair<List<Condition>, Map<String, Any?>> {
        return if (field.type.isNeo4jType() && value is ObjectValue && !distance) {
            resolveNeo4jTypeConditions(variablePrefix, queriedField, propertyContainer, field, value)
        } else {
            resolveCondition(variablePrefix, queriedField, propertyContainer.property(field.name), value)
        }
    }

    private fun resolveNeo4jTypeConditions(variablePrefix: String, queriedField: String, propertyContainer: PropertyContainer, field: GraphQLFieldDefinition, value: ObjectValue): Pair<MutableList<Condition>, MutableMap<String, Any?>> {
        val conditions = mutableListOf<Condition>()
        val params = mutableMapOf<String, Any?>()
        for (objectField in value.objectFields) {
            val (childConditions, childParams) = resolveCondition(
                    variablePrefix + "_" + queriedField,
                    objectField.name,
                    propertyContainer.property(field.name + "." + objectField.name),
                    objectField.value
            )
            conditions += childConditions
            params += childParams
        }
        return conditions to params
    }

    private fun resolveCondition(variablePrefix: String, queriedField: String, property: Property, value: Any): Pair<List<Condition>, Map<String, Any?>> {
        val parameter = org.neo4j.cypherdsl.core.Cypher.parameter(variablePrefix + "_" + queriedField)
        val condition = conditionCreator(property, parameter)
        val params = if (requireParam) {
            mapOf(parameter.name to value.toJavaValue())
        } else {
            emptyMap<String, Any?>()
        }
        return listOf(condition) to params
    }

    companion object {

        fun distanceOp(lhs: Expression, rhs: Parameter, op: FieldOperator): Condition {
            val point = point(CypherDSL.parameter(rhs.name + ".point"))
            val distance = CypherDSL.parameter(rhs.name + ".distance")
            return op.conditionCreator(Functions.distance(lhs, point), distance)
        }

        fun resolve(queriedField: String, field: GraphQLFieldDefinition, value: Any?): FieldOperator? {
            val fieldName = field.name
            if (value == null) {
                return listOf(IS_NULL, IS_NOT_NULL).find { queriedField == fieldName + it.suffix }
            }
            val ops = enumValues<FieldOperator>().filterNot { it == IS_NULL || it == IS_NOT_NULL }
            return ops.find { queriedField == fieldName + it.suffix }
                    ?: if (field.type.isNeo4jSpatialType()) {
                        ops.find { queriedField == fieldName + NEO4j_POINT_DISTANCE_FILTER_SUFFIX + it.suffix }
                    } else {
                        null
                    }
        }

        fun forType(type: GraphQLType): List<FieldOperator> =
                when {
                    type == Scalars.GraphQLBoolean -> listOf(EQ, NEQ)
                    type.innerName() == NEO4j_POINT_DISTANCE_FILTER -> listOf(EQ, LT, LTE, GT, GTE)
                    type.isNeo4jSpatialType() -> listOf(EQ, NEQ)
                    type.isNeo4jType() -> listOf(EQ, NEQ, IN, NIN)
                    type is GraphQLFieldsContainer || type is GraphQLInputObjectType -> throw IllegalArgumentException("This operators are not for relations, use the RelationOperator instead")
                    type is GraphQLEnumType -> listOf(EQ, NEQ, IN, NIN)
                    // todo list types
                    !type.isScalar() -> listOf(EQ, NEQ, IN, NIN)
                    else -> listOf(EQ, NEQ, IN, NIN, LT, LTE, GT, GTE) +
                            if (type.name() == "String" || type.name() == "ID") listOf(C, NC, SW, NSW, EW, NEW) else emptyList()
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

    fun harmonize(type: GraphQLFieldsContainer, field: GraphQLFieldDefinition, value: Value<*>, queryFieldName: String) = when (field.type.isList()) {
        true -> when (this) {
            NOT -> when (value) {
                is NullValue -> NOT
                else -> NONE
            }
            EQ_OR_NOT_EXISTS -> when (value) {
                is NullValue -> EQ_OR_NOT_EXISTS
                else -> {
                    LOGGER.debug("$queryFieldName on type ${type.name} was used for filtering, consider using ${field.name}${EVERY.suffix} instead")
                    EVERY
                }
            }
            else -> this
        }
        false -> when (this) {
            SINGLE -> {
                LOGGER.debug("Using $queryFieldName on type ${type.name} is deprecated, use ${field.name} directly")
                SOME
            }
            SOME -> {
                LOGGER.debug("Using $queryFieldName on type ${type.name} is deprecated, use ${field.name} directly")
                SOME
            }
            NONE -> {
                LOGGER.debug("Using $queryFieldName on type ${type.name} is deprecated, use ${field.name}${NOT.suffix} instead")
                NONE
            }
            NOT -> when (value) {
                is NullValue -> NOT
                else -> NONE
            }
            EQ_OR_NOT_EXISTS -> when (value) {
                is NullValue -> EQ_OR_NOT_EXISTS
                else -> SOME
            }
            else -> this
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(RelationOperator::class.java)

        fun createRelationFilterFields(type: GraphQLFieldsContainer, field: GraphQLFieldDefinition, filterType: String, builder: GraphQLInputObjectType.Builder) {
            val list = field.type.isList()

            val addFilterField = { op: RelationOperator, description: String ->
                builder.addFilterField(op.fieldName(field.name), false, filterType, description)
            }

            addFilterField(EQ_OR_NOT_EXISTS, "Filters only those `${type.name}` for which ${if (list) "all" else "the"} `${field.name}`-relationship matches this filter. " +
                    "If `null` is passed to this field, only those `${type.name}` will be filtered which has no `${field.name}`-relations")

            addFilterField(NOT, "Filters only those `${type.name}` for which ${if (list) "all" else "the"} `${field.name}`-relationship does not match this filter. " +
                    "If `null` is passed to this field, only those `${type.name}` will be filtered which has any `${field.name}`-relation")
            if (list) {
                // n..m
                addFilterField(EVERY, "Filters only those `${type.name}` for which all `${field.name}`-relationships matches this filter")
                addFilterField(SOME, "Filters only those `${type.name}` for which at least one `${field.name}`-relationship matches this filter")
                addFilterField(SINGLE, "Filters only those `${type.name}` for which exactly one `${field.name}`-relationship matches this filter")
                addFilterField(NONE, "Filters only those `${type.name}` for which none of the `${field.name}`-relationships matches this filter")
            } else {
                // n..1
                addFilterField(SINGLE, "@deprecated Use the `${field.name}`-field directly (without any suffix)")
                addFilterField(SOME, "@deprecated Use the `${field.name}`-field directly (without any suffix)")
                addFilterField(NONE, "@deprecated Use the `${field.name}${NOT.suffix}`-field")
            }
        }
    }
}
