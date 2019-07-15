package org.neo4j.graphql

import graphql.Scalars
import graphql.language.*
import graphql.schema.*
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER_STATEMENT
import org.neo4j.graphql.DirectiveConstants.Companion.DYNAMIC
import org.neo4j.graphql.DirectiveConstants.Companion.DYNAMIC_PREFIX
import org.neo4j.graphql.DirectiveConstants.Companion.NATIVE_ID
import org.neo4j.graphql.DirectiveConstants.Companion.PROPERTY
import org.neo4j.graphql.DirectiveConstants.Companion.PROPERTY_NAME
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_DIRECTION_OUT
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_FROM
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_NAME
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_TO

fun GraphQLType.inner(): GraphQLType = when (this) {
    is GraphQLList -> this.wrappedType.inner()
    is GraphQLNonNull -> this.wrappedType.inner()
    else -> this
}

val SCALAR_TYPES = listOf("String", "ID", "Boolean", "Int", "Float", "Object")

fun Type<Type<*>>.isScalar() = this.inner().name()?.let { SCALAR_TYPES.contains(it) } ?: false
fun Type<Type<*>>.name(): String? = if (this.inner() is TypeName) (this.inner() as TypeName).name else null

fun Type<Type<*>>.inner(): Type<Type<*>> = when (this) {
    is ListType -> this.type.inner()
    is NonNullType -> this.type.inner()
    else -> this
}

fun Type<Type<*>>.render(nonNull: Boolean = true): String = when (this) {
    is ListType -> "[${this.type.render()}]"
    is NonNullType -> this.type.render() + (if (nonNull) "!" else "")
    is TypeName -> this.name
    else -> throw IllegalStateException("Can't render $this")
}

fun GraphQLType.isList() = this is GraphQLList || (this is GraphQLNonNull && this.wrappedType is GraphQLList)
fun GraphQLType.isScalar() = this.inner().let { it is GraphQLScalarType || it.name.startsWith("_Neo4j") }
fun GraphQLType.isRelationship() = this.inner().let { it is GraphQLObjectType }
fun GraphQLType.isRelationshipType() = this.inner().let { it is GraphQLObjectType && it.getDirective(RELATION) != null }

fun GraphQLObjectType.hasRelationship(name: String) = this.getFieldDefinition(name)?.isRelationship() ?: false
fun GraphQLObjectType.getRelationshipName() = this.getDirective(RELATION)?.getArgument(RELATION_NAME)?.value?.toString()
fun ObjectTypeDefinition.relationship(defaultValueProvider: (directiveName: String, arg: String) -> String?): RelationshipInfo? {
    val relDirective = this.getDirective(RELATION) ?: return null
    val directiveResolver: (name: String, defaultValue: String?) -> String = { param, defaultValue ->
        relDirective.getArgument(param)?.value?.toJavaValue()?.toString()
                ?: defaultValueProvider(relDirective.name, param)
                ?: defaultValue
                ?: throw IllegalStateException("No default value for ${relDirective.name}.$param")
    }
    val relType = directiveResolver(RELATION_NAME, "")
    val startField = directiveResolver(RELATION_FROM, null)
    val endField = directiveResolver(RELATION_TO, null)
    return RelationshipInfo(this, relType, null, startField, endField)
}

fun GraphQLObjectType.relationship(schema: GraphQLSchema): RelationshipInfo? {
    return this.definition.relationship { directiveName, arg -> schema.getDirective(directiveName)?.getArgument(arg)?.defaultValue?.toString() }
}

fun GraphQLObjectType.relationshipFor(name: String, schema: GraphQLSchema): RelationshipInfo {
    return this.relationshipFor(name, schema::getType, schema::getDirective)
}

fun GraphQLObjectType.relationshipFor(name: String, typeResolver: (String) -> GraphQLType, definitionProvider: (String) -> GraphQLDirective): RelationshipInfo {
    val field = this.getFieldDefinition(name)
    val fieldObjectType = typeResolver(field.type.inner().name) as GraphQLObjectType
    // direction
    // label
    // out

    // TODO direction is depending on source/target type

    val (relDirective, isRelFromType) = fieldObjectType.definition.getDirective(RELATION)?.let { it to true }
            ?: field.definition.getDirective(RELATION)?.let { it to false }
            ?: throw IllegalStateException("Field $field needs an @relation directive")


    val relInfo = relDetails(fieldObjectType.definition) { argName, defaultValue ->
        relDirective.getArgument(argName)?.value?.toJavaValue()?.toString()
                ?: definitionProvider(relDirective.name).getArgument(argName)?.defaultValue?.toString()
                ?: defaultValue
                ?: throw IllegalStateException("No default value for ${relDirective.name}.$argName")
    }

    val inverse = isRelFromType && fieldObjectType.getFieldDefinition(relInfo.startField).name != this.name
    return if (inverse) relInfo.copy(out = relInfo.out?.let { !it }, startField = relInfo.endField, endField = relInfo.startField) else relInfo
}

fun relDetails(type: ObjectTypeDefinition,
        directiveResolver: (name: String, defaultValue: String?) -> String): RelationshipInfo {
    val relType = directiveResolver(RELATION_NAME, "")
    val outgoing = when (directiveResolver(RELATION_DIRECTION, null)) {
        DirectiveConstants.RELATION_DIRECTION_IN -> false
        DirectiveConstants.RELATION_DIRECTION_BOTH -> null
        RELATION_DIRECTION_OUT -> true
        else -> throw IllegalStateException("Unknown direction ${directiveResolver(RELATION_DIRECTION, null)}")
    }
    return RelationshipInfo(type,
            relType,
            outgoing,
            directiveResolver(RELATION_FROM, null),
            directiveResolver(RELATION_TO, null))
}

fun arrows(outgoing: Boolean?): Pair<String, String> {
    return when (outgoing) {
        false -> "<" to ""
        true -> "" to ">"
        null -> "" to ""
    }
}

data class RelationshipInfo(
        val type: ObjectTypeDefinition,
        val relType: String,
        val out: Boolean?,
        val startField: String? = null,
        val endField: String? = null,
        val isRelFromType: Boolean = false
) {
    data class RelatedField(
            val argumentName: String,
            val field: FieldDefinition,
            val declaringType: ObjectTypeDefinition
    )

    val arrows = arrows(out)

    val typeName: String get() = this.type.name

    fun getStartFieldIds(typeResolver: (String?) -> ObjectTypeDefinition?) = getRelatedIdFields(this.startField, typeResolver)

    fun getEndFieldIds(typeResolver: (String?) -> ObjectTypeDefinition?) = getRelatedIdFields(this.endField, typeResolver)

    fun getRelatedIdFields(relFieldName: String?, typeResolver: (String?) -> ObjectTypeDefinition?): List<RelatedField> {

        if (relFieldName == null) return emptyList()
        val relFieldDefinition = type.fieldDefinitions.find { it.name == relFieldName }
                ?: throw IllegalArgumentException("field $relFieldName does not exists on ${type.name}")
        val relType = typeResolver(relFieldDefinition.type.name())
                ?: throw IllegalArgumentException("type ${relFieldDefinition.type.name()} not found")
        return (relType as? ObjectTypeDefinition)
            ?.fieldDefinitions
            ?.filter { it.isID() || it.isNativeId() }
            ?.map { RelatedField("${relFieldName}_${it.name}", it, relType) }
                ?: emptyList()
    }
}

fun GraphQLFieldDefinition.isRelationship() = this.type.isRelationship()

fun Field.aliasOrName() = (this.alias ?: this.name).quote()
fun Field.propertyName(fieldDefinition: GraphQLFieldDefinition) = (fieldDefinition.propertyDirectiveName()
        ?: this.name).quote()

fun GraphQLFieldDefinition.propertyDirectiveName() =
        this.definition.getDirective(PROPERTY)?.getArgument(PROPERTY_NAME)?.value?.toJavaValue()?.toString()

fun GraphQLFieldDefinition.isNativeId() = this.definition.getDirective(NATIVE_ID) != null

fun GraphQLFieldDefinition.dynamicPrefix(schema: GraphQLSchema): String? {
    val directive = this.definition.getDirective(DYNAMIC)
    return if (directive != null) {
        directive.argumentString(DYNAMIC_PREFIX, schema)
    } else null
}

fun GraphQLFieldDefinition.cypherDirective(): Translator.Cypher? =
        this.definition.getDirective(CYPHER)?.let {
            @Suppress("UNCHECKED_CAST")
            Translator.Cypher(it.getArgument(CYPHER_STATEMENT).value.toJavaValue().toString(),
                    it.getArgument("params")?.value?.toJavaValue() as Map<String, Any?>? ?: emptyMap())
        }

fun String.quote() = if (isJavaIdentifier()) this else "`$this`"

fun String.isJavaIdentifier() =
        this[0].isJavaIdentifierStart() &&
                this.substring(1).all { it.isJavaIdentifierPart() }

fun Directive.argumentString(name: String, schema: GraphQLSchema, defaultValue: String? = null): String {
    return this.getArgument(name)?.value?.toJavaValue()?.toString()
            ?: schema.getDirective(this.name).getArgument(name)?.defaultValue?.toString()
            ?: defaultValue
            ?: throw IllegalStateException("No default value for ${this.name}.$name")
}

fun Value<Value<*>>.toCypherString(): String = when (this) {
    is StringValue -> "'" + this.value + "'"
    is EnumValue -> "'" + this.name + "'"
    is NullValue -> "null"
    is BooleanValue -> this.isValue.toString()
    is FloatValue -> this.value.toString()
    is IntValue -> this.value.toString()
    is VariableReference -> "$" + this.name
    is ArrayValue -> this.values.joinToString(",", "[", "]") { it.toCypherString() }
    else -> throw IllegalStateException("Unhandled value $this")
}

fun Value<Value<*>>.toJavaValue(): Any? = when (this) {
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

fun FieldDefinition.isID(): Boolean = this.type.name() == "ID"
fun FieldDefinition.isNativeId() = this.getDirective("nativeId") != null

fun FieldDefinition.isList(): Boolean = this.type is ListType
fun GraphQLFieldDefinition.isID(): Boolean = this.type.inner() == Scalars.GraphQLID
fun ObjectTypeDefinition.getFieldByType(typeName: String): FieldDefinition? = this.fieldDefinitions
    .firstOrNull { it.type.inner().name() == typeName }

fun ObjectTypeDefinition.isRealtionType(): Boolean = this.getDirective(RELATION) != null

fun GraphQLDirective.getRelationshipType(): String = this.getArgument(RELATION_NAME).value.toString()
fun GraphQLDirective.getRelationshipDirection(): String = this.getArgument(RELATION_DIRECTION)?.value?.toString()
        ?: RELATION_DIRECTION_OUT
