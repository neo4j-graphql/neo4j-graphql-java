package org.neo4j.graphql

import graphql.Scalars
import graphql.language.*
import graphql.schema.*
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER_STATEMENT
import org.neo4j.graphql.DirectiveConstants.Companion.DYNAMIC
import org.neo4j.graphql.DirectiveConstants.Companion.DYNAMIC_PREFIX
import org.neo4j.graphql.DirectiveConstants.Companion.PROPERTY
import org.neo4j.graphql.DirectiveConstants.Companion.PROPERTY_NAME
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION_BOTH
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION_IN
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION_OUT
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_FROM
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_NAME
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_TO
import org.neo4j.graphql.handler.projection.ProjectionBase

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

fun GraphQLType.isList() = this is GraphQLList || (this is GraphQLNonNull && this.wrappedType is GraphQLList)
fun GraphQLType.isScalar() = this.inner().let { it is GraphQLScalarType || it.name.startsWith("_Neo4j") }
fun GraphQLType.isNeo4jType() = this.inner().name?.startsWith("_Neo4j") == true
fun GraphQLFieldDefinition.isNeo4jType(): Boolean = this.type.isNeo4jType()

fun GraphQLFieldDefinition.isRelationship() = !type.isNeo4jType()  && this.type.inner().let { it is GraphQLFieldsContainer }

fun GraphQLFieldsContainer.hasRelationship(name: String) = this.getFieldDefinition(name)?.isRelationship() ?: false
fun GraphQLDirectiveContainer.isRelationType() = getDirective(DirectiveConstants.RELATION) != null
fun GraphQLFieldsContainer.isRelationType() = (this as? GraphQLDirectiveContainer)?.getDirective(DirectiveConstants.RELATION) != null
fun GraphQLFieldsContainer.relationshipFor(name: String): RelationshipInfo? {
    val field = getFieldDefinition(name)
            ?: throw IllegalArgumentException("$name is not defined on ${this.name}")
    val fieldObjectType = field.type.inner() as? GraphQLFieldsContainer ?: return null

    val (relDirective, isRelFromType) = if (isRelationType()){
        (this as? GraphQLDirectiveContainer)
            ?.getDirective(DirectiveConstants.RELATION)?.let { it to false }
                ?: throw IllegalStateException("Field $field needs an @relation directive")
    } else {
        (fieldObjectType as? GraphQLDirectiveContainer)
            ?.getDirective(DirectiveConstants.RELATION)?.let { it to true }
                ?: field.getDirective(DirectiveConstants.RELATION)?.let { it to false }
                ?: throw IllegalStateException("Field $field needs an @relation directive")
    }

    // TODO direction is depending on source/target type

    val relInfo = relDetails(fieldObjectType) { argName, defaultValue -> relDirective.getArgument(argName, defaultValue) }

    val inverse = isRelFromType && fieldObjectType.getFieldDefinition(relInfo.startField)?.name != this.name
    return if (inverse) relInfo.copy(out = relInfo.out?.let { !it }, startField = relInfo.endField, endField = relInfo.startField) else relInfo
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

@Suppress("SimplifiableCallChain")
fun GraphQLFieldsContainer.label(includeAll: Boolean = false, quote: Boolean = true) = when {
    this.isRelationType() ->
        (this as? GraphQLDirectiveContainer)
            ?.getDirective(DirectiveConstants.RELATION)
            ?.getArgument(RELATION_NAME)?.value?.toJavaValue()?.toString()?.let { if (quote) it.quote() else it  }
                ?: this.name.let { if (quote) it.quote() else it  }
    else -> when {
        includeAll -> (listOf(name) + ((this as? GraphQLObjectType)?.interfaces?.map { it.name } ?: emptyList()))
            .map { it.quote() }
            .joinToString(":")
        else -> name.let { if (quote) it.quote() else it  }
    }
}

fun GraphQLFieldsContainer.relevantFields() = fieldDefinitions
    .filter { it.type.isScalar() || it.isNeo4jType() }
    .sortedByDescending { it.isID() }

fun GraphQLFieldsContainer.relationship(): RelationshipInfo? {
    val relDirective = (this as? GraphQLDirectiveContainer)?.getDirective(DirectiveConstants.RELATION) ?: return null
    val directiveResolver: (name: String, defaultValue: String?) -> String? = { argName, defaultValue ->
        relDirective.getArgument(argName, defaultValue)
    }
    val relType = directiveResolver(RELATION_NAME, "")!!
    val startField = directiveResolver(RELATION_FROM, null)
    val endField = directiveResolver(RELATION_TO, null)
    return RelationshipInfo(this, relType, null, startField, endField)
}

fun GraphQLType.ref(): GraphQLType = when (this) {
    is GraphQLNonNull -> GraphQLNonNull(this.wrappedType.ref())
    is GraphQLList -> GraphQLList(this.wrappedType.ref())
    is GraphQLScalarType -> this
    is GraphQLEnumType -> this
    is GraphQLTypeReference -> this
    else -> GraphQLTypeReference(name)
}

fun relDetails(type: GraphQLFieldsContainer,
        // TODO simplify uasage (no more callback)
        directiveResolver: (name: String, defaultValue: String?) -> String?): RelationshipInfo {
    val relType = directiveResolver(RELATION_NAME, "")!!
    val outgoing = when (directiveResolver(RELATION_DIRECTION, null)) {
        RELATION_DIRECTION_IN -> false
        RELATION_DIRECTION_BOTH -> null
        RELATION_DIRECTION_OUT -> true
        else -> throw IllegalStateException("Unknown direction ${directiveResolver(RELATION_DIRECTION, null)}")
    }
    return RelationshipInfo(type,
            relType,
            outgoing,
            directiveResolver(RELATION_FROM, null),
            directiveResolver(RELATION_TO, null))
}

data class RelationshipInfo(
        val type: GraphQLFieldsContainer,
        val relType: String,
        val out: Boolean?,
        val startField: String? = null,
        val endField: String? = null,
        val isRelFromType: Boolean = false
) {
    data class RelatedField(
            val argumentName: String,
            val field: GraphQLFieldDefinition,
            val declaringType: GraphQLFieldsContainer
    )

    val arrows = when (out) {
        false -> "<" to ""
        true -> "" to ">"
        null -> "" to ""
    }

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
            .map { RelatedField("${relFieldName}_${it.name}", it, relType) }
            .firstOrNull()
    }
}

fun Field.aliasOrName() = (this.alias ?: this.name).quote()
fun GraphQLType.innerName(): String = inner().name

fun GraphQLFieldDefinition.propertyName() = getDirectiveArgument(PROPERTY, PROPERTY_NAME, this.name)!!

fun GraphQLFieldDefinition.dynamicPrefix(): String? = getDirectiveArgument(DYNAMIC, DYNAMIC_PREFIX, null)
fun GraphQLType.getInnerFieldsContainer() = inner() as? GraphQLFieldsContainer
        ?: throw IllegalArgumentException("${this.innerName()} is neither an object nor an interface")

fun <T> GraphQLDirectiveContainer.getDirectiveArgument(directiveName: String, argumentName: String, defaultValue: T?): T? =
        getDirective(directiveName)?.getArgument(argumentName, defaultValue) ?: defaultValue

fun <T> GraphQLDirective.getArgument(argumentName: String, defaultValue: T?): T? {
    val argument = getArgument(argumentName)
    @Suppress("UNCHECKED_CAST")
    return argument?.value as T?
            ?: argument.defaultValue as T?
            ?: defaultValue
            ?: throw IllegalStateException("No default value for @${this.name}::$argumentName")
}

fun GraphQLFieldDefinition.cypherDirective(): Cypher? = getDirectiveArgument<String>(CYPHER, CYPHER_STATEMENT, null)
    ?.let { statement -> Cypher(statement) }

fun String.quote() = if (isJavaIdentifier()) this else "`$this`"

fun String.isJavaIdentifier() =
        this[0].isJavaIdentifierStart() &&
                this.substring(1).all { it.isJavaIdentifierPart() }

@Suppress("SimplifiableCallChain")
fun Value<Value<*>>.toCypherString(): String = when (this) {
    is StringValue -> "'" + this.value + "'"
    is EnumValue -> "'" + this.name + "'"
    is NullValue -> "null"
    is BooleanValue -> this.isValue.toString()
    is FloatValue -> this.value.toString()
    is IntValue -> this.value.toString()
    is VariableReference -> "$" + this.name
    is ArrayValue -> this.values.map { it.toCypherString() }.joinToString(",", "[", "]")
    else -> throw IllegalStateException("Unhandled value $this")
}

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

fun paramName(variable: String, argName: String, value: Any?): String = when (value) {
    is VariableReference -> value.name
    else -> "$variable${argName.capitalize()}"
}

fun GraphQLFieldDefinition.isID() = this.type.inner() == Scalars.GraphQLID
fun GraphQLFieldDefinition.isNativeId() = this.name == ProjectionBase.NATIVE_ID
fun GraphQLFieldsContainer.getIdField() = this.fieldDefinitions.find { it.isID() }