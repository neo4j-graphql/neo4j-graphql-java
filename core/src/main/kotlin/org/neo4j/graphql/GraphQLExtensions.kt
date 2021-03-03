package org.neo4j.graphql

import graphql.Scalars
import graphql.language.*
import graphql.schema.*
import org.neo4j.cypherdsl.core.Node
import org.neo4j.cypherdsl.core.Relationship
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER_STATEMENT
import org.neo4j.graphql.DirectiveConstants.Companion.DYNAMIC
import org.neo4j.graphql.DirectiveConstants.Companion.DYNAMIC_PREFIX
import org.neo4j.graphql.DirectiveConstants.Companion.PROPERTY
import org.neo4j.graphql.DirectiveConstants.Companion.PROPERTY_NAME
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_FROM
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_NAME
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_TO
import org.neo4j.graphql.handler.projection.ProjectionBase
import java.math.BigDecimal
import java.math.BigInteger

fun Type<Type<*>>.name(): String? = if (this.inner() is TypeName) (this.inner() as TypeName).name else null
fun Type<Type<*>>.inner(): Type<Type<*>> = when (this) {
    is ListType -> this.type.inner()
    is NonNullType -> this.type.inner()
    else -> this
}

fun GraphQLType.inner(): GraphQLType = when (this) {
    is GraphQLList -> this.wrappedType.inner()
    is GraphQLNonNull -> this.wrappedType.inner()
    else -> this
}

fun GraphQLType.name(): String? = (this as? GraphQLNamedType)?.name
fun GraphQLType.requiredName(): String = requireNotNull(name()) { "name is required but cannot be determined for " + this.javaClass }

fun GraphQLType.isList() = this is GraphQLList || (this is GraphQLNonNull && this.wrappedType is GraphQLList)
fun GraphQLType.isScalar() = this.inner().let { it is GraphQLScalarType || it.innerName().startsWith("_Neo4j") }
fun GraphQLType.isNeo4jType() = this.innerName().startsWith("_Neo4j")
fun GraphQLType.isNeo4jSpatialType() = this.innerName().startsWith("_Neo4jPoint")
fun GraphQLFieldDefinition.isNeo4jType(): Boolean = this.type.isNeo4jType()

fun GraphQLFieldDefinition.isRelationship() = !type.isNeo4jType() && this.type.inner().let { it is GraphQLFieldsContainer }

fun GraphQLDirectiveContainer.isRelationType() = getDirective(DirectiveConstants.RELATION) != null
fun GraphQLFieldsContainer.isRelationType() = (this as? GraphQLDirectiveContainer)?.getDirective(DirectiveConstants.RELATION) != null
fun GraphQLFieldsContainer.relationshipFor(name: String): RelationshipInfo? {
    val field = getFieldDefinition(name)
            ?: throw IllegalArgumentException("$name is not defined on ${this.name}")
    val fieldObjectType = field.type.inner() as? GraphQLFieldsContainer ?: return null

    val (relDirective, inverse) = if (isRelationType()) {
        val typeName = this.name
        (this as? GraphQLDirectiveContainer)
            ?.getDirective(DirectiveConstants.RELATION)?.let {
                // do inverse mapping, if the current type  is the `to` mapping of the relation
                it to (fieldObjectType.getFieldDefinition(it.getArgument(RELATION_TO, null))?.name == typeName)
            }
                ?: throw IllegalStateException("Type ${this.name} needs an @relation directive")
    } else {
        (fieldObjectType as? GraphQLDirectiveContainer)
            ?.getDirective(DirectiveConstants.RELATION)?.let { it to true }
                ?: field.getDirective(DirectiveConstants.RELATION)?.let { it to false }
                ?: throw IllegalStateException("Field $field needs an @relation directive")
    }

    val relInfo = relDetails(fieldObjectType, relDirective)

    return if (inverse) relInfo.copy(direction = relInfo.direction.invert(), startField = relInfo.endField, endField = relInfo.startField) else relInfo
}

fun GraphQLFieldsContainer.getValidTypeLabels(schema: GraphQLSchema): List<String> {
    if (this is GraphQLObjectType) {
        return listOf(this.label())
    }
    if (this is GraphQLInterfaceType) {
        return schema.getImplementations(this)
            .mapNotNull { it.label() }
    }
    return emptyList()
}

fun GraphQLFieldsContainer.label(): String = when {
    this.isRelationType() ->
        (this as? GraphQLDirectiveContainer)
            ?.getDirective(DirectiveConstants.RELATION)
            ?.getArgument(RELATION_NAME)?.value?.toJavaValue()?.toString()
                ?: this.name
    else -> name
}


fun GraphQLFieldsContainer.relevantFields() = fieldDefinitions
    .filter { it.type.isScalar() || it.isNeo4jType() }
    .sortedByDescending { it.isID() }

fun GraphQLFieldsContainer.relationship(): RelationshipInfo? {
    val relDirective = (this as? GraphQLDirectiveContainer)?.getDirective(DirectiveConstants.RELATION) ?: return null
    val relType = relDirective.getArgument(RELATION_NAME, "")!!
    val startField = relDirective.getMandatoryArgument<String>(RELATION_FROM)
    val endField = relDirective.getMandatoryArgument<String>(RELATION_TO)
    val direction = relDirective.getArgument<String>(RELATION_DIRECTION)?.let { RelationDirection.valueOf(it) }
            ?: RelationDirection.OUT
    return RelationshipInfo(this, relType, direction, startField, endField)
}

fun GraphQLType.ref(): GraphQLType = when (this) {
    is GraphQLNonNull -> GraphQLNonNull(this.wrappedType.ref())
    is GraphQLList -> GraphQLList(this.wrappedType.ref())
    is GraphQLScalarType -> this
    is GraphQLEnumType -> this
    is GraphQLTypeReference -> this
    else -> GraphQLTypeReference(name())
}

fun relDetails(type: GraphQLFieldsContainer, relDirective: GraphQLDirective): RelationshipInfo {
    val relType = relDirective.getArgument(RELATION_NAME, "")!!
    val direction = relDirective.getArgument<String>(RELATION_DIRECTION, null)
        ?.let { RelationDirection.valueOf(it) }
            ?: RelationDirection.OUT

    return RelationshipInfo(type,
            relType,
            direction,
            relDirective.getMandatoryArgument(RELATION_FROM),
            relDirective.getMandatoryArgument(RELATION_TO)
    )
}

data class RelationshipInfo(
        val type: GraphQLFieldsContainer,
        val relType: String,
        val direction: RelationDirection,
        val startField: String,
        val endField: String
) {
    data class RelatedField(
            val argumentName: String,
            val field: GraphQLFieldDefinition,
            val declaringType: GraphQLFieldsContainer
    )

    val typeName: String get() = this.type.name

    fun getStartFieldId() = getRelatedIdField(this.startField)

    fun getEndFieldId() = getRelatedIdField(this.endField)

    private fun getRelatedIdField(relFieldName: String?): RelatedField? {
        if (relFieldName == null) return null
        val relFieldDefinition = type.getFieldDefinition(relFieldName)
                ?: throw IllegalArgumentException("field $relFieldName does not exists on ${type.innerName()}")
        val relType = relFieldDefinition.type.inner() as? GraphQLFieldsContainer
                ?: throw IllegalArgumentException("type ${relFieldDefinition.type.innerName()} not found")
        return relType.fieldDefinitions.filter { it.isID() }
            .map {
                // TODO b/c we need to stay backwards kompatible this is not caml case but with underscore
                //val filedName = normalizeName(relFieldName, it.name)
                val filedName = "${relFieldName}_${it.name}"
                RelatedField(filedName, it, relType)
            }
            .firstOrNull()
    }

    fun createRelation(start: Node, end: Node): Relationship =
            when (this.direction) {
                RelationDirection.IN -> start.relationshipFrom(end, this.relType)
                RelationDirection.OUT -> start.relationshipTo(end, this.relType)
                RelationDirection.BOTH -> start.relationshipBetween(end, this.relType)
            }
}

fun Field.aliasOrName(): String = (this.alias ?: this.name)
fun Field.contextualize(variable: String) = variable + this.aliasOrName().capitalize()
fun Field.contextualize(variable: SymbolicName) = variable.value + this.aliasOrName().capitalize()

fun GraphQLType.innerName(): String = inner().name()
        ?: throw IllegalStateException("inner name cannot be retrieved for " + this.javaClass)

fun GraphQLFieldDefinition.propertyName() = getDirectiveArgument(PROPERTY, PROPERTY_NAME, this.name)!!

fun GraphQLFieldDefinition.dynamicPrefix(): String? = getDirectiveArgument(DYNAMIC, DYNAMIC_PREFIX, null)
fun GraphQLType.getInnerFieldsContainer() = inner() as? GraphQLFieldsContainer
        ?: throw IllegalArgumentException("${this.innerName()} is neither an object nor an interface")

fun <T> GraphQLDirectiveContainer.getDirectiveArgument(directiveName: String, argumentName: String, defaultValue: T?): T? =
        getDirective(directiveName)?.getArgument(argumentName, defaultValue) ?: defaultValue

fun <T> GraphQLDirective.getMandatoryArgument(argumentName: String, defaultValue: T? = null): T = this.getArgument(argumentName, defaultValue)
        ?: throw IllegalStateException(argumentName + " is required for @${this.name}")

fun <T> GraphQLDirective.getArgument(argumentName: String, defaultValue: T? = null): T? {
    val argument = getArgument(argumentName)
    @Suppress("UNCHECKED_CAST")
    return argument?.value as T?
            ?: argument.defaultValue as T?
            ?: defaultValue
            ?: throw IllegalStateException("No default value for @${this.name}::$argumentName")
}

fun GraphQLFieldDefinition.cypherDirective(): Cypher? = getDirectiveArgument<String>(CYPHER, CYPHER_STATEMENT, null)
    ?.let { statement -> Cypher(statement) }

fun Any.toJavaValue() = when (this) {
    is Value<*> -> this.toJavaValue()
    else -> this
}

fun Value<*>.toJavaValue(): Any? = when (this) {
    is StringValue -> this.value
    is EnumValue -> this.name
    is NullValue -> null
    is BooleanValue -> this.isValue
    is FloatValue -> this.value.toDouble()
    is IntValue -> this.value.longValueExact()
    is VariableReference -> this
    is ArrayValue -> this.values.map { it.toJavaValue() }.toList()
    is ObjectValue -> this.objectFields.map { it.name to it.value.toJavaValue() }.toMap()
    else -> throw IllegalStateException("Unhandled value $this")
}

fun GraphQLFieldDefinition.isID() = this.type.inner() == Scalars.GraphQLID
fun GraphQLFieldDefinition.isNativeId() = this.name == ProjectionBase.NATIVE_ID
fun GraphQLFieldsContainer.getIdField() = this.fieldDefinitions.find { it.isID() }

fun GraphQLInputObjectType.Builder.addFilterField(fieldName: String, isList: Boolean, filterType: String, description: String? = null) {
    val wrappedType: GraphQLInputType = when {
        isList -> GraphQLList(GraphQLTypeReference(filterType))
        else -> GraphQLTypeReference(filterType)
    }
    val inputField = GraphQLInputObjectField.newInputObjectField()
        .name(fieldName)
        .description(description)
        .type(wrappedType)
    this.field(inputField)
}


fun GraphQLSchema.queryTypeName() = this.queryType?.name ?: "Query"
fun GraphQLSchema.mutationTypeName() = this.mutationType?.name ?: "Mutation"
fun GraphQLSchema.subscriptionTypeName() = this.subscriptionType?.name ?: "Subscription"

fun Any?.asGraphQLValue(): Value<*> = when (this) {
    null -> NullValue.newNullValue().build()
    is Value<*> -> this
    is Array<*> -> ArrayValue.newArrayValue().values(this.map { it.asGraphQLValue() }).build()
    is Iterable<*> -> ArrayValue.newArrayValue().values(this.map { it.asGraphQLValue() }).build()
    is Map<*, *> -> ObjectValue.newObjectValue().objectFields(this.map { entry -> ObjectField(entry.key as String, entry.value.asGraphQLValue()) }).build()
    is Enum<*> -> EnumValue.newEnumValue().name(this.name).build()
    is Int -> IntValue.newIntValue(BigInteger.valueOf(this.toLong())).build()
    is Long -> IntValue.newIntValue(BigInteger.valueOf(this)).build()
    is Number -> FloatValue.newFloatValue(BigDecimal.valueOf(this as Double)).build()
    is Boolean -> BooleanValue.newBooleanValue(this).build()
    is String -> StringValue.newStringValue(this).build()
    else -> throw IllegalStateException("Cannot convert ${this.javaClass.name} into an graphql type")
}
