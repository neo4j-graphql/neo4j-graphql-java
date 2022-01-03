package org.neo4j.graphql

import graphql.Scalars
import graphql.language.*
import graphql.language.TypeDefinition
import graphql.schema.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER_PASS_THROUGH
import org.neo4j.graphql.DirectiveConstants.Companion.CYPHER_STATEMENT
import org.neo4j.graphql.DirectiveConstants.Companion.DYNAMIC
import org.neo4j.graphql.DirectiveConstants.Companion.DYNAMIC_PREFIX
import org.neo4j.graphql.DirectiveConstants.Companion.PROPERTY
import org.neo4j.graphql.DirectiveConstants.Companion.PROPERTY_NAME
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_NAME
import org.neo4j.graphql.DirectiveConstants.Companion.RELATION_TO
import org.neo4j.graphql.handler.projection.ProjectionBase
import org.slf4j.LoggerFactory

fun Type<*>.name(): String? = if (this.inner() is TypeName) (this.inner() as TypeName).name else null
fun Type<*>.inner(): Type<*> = when (this) {
    is ListType -> this.type.inner()
    is NonNullType -> this.type.inner()
    else -> this
}

fun Type<*>.isList(): Boolean = when (this) {
    is ListType -> true
    is NonNullType -> type.isList()
    else -> false
}

fun GraphQLType.inner(): GraphQLType = when (this) {
    is GraphQLList -> this.wrappedType.inner()
    is GraphQLNonNull -> this.wrappedType.inner()
    else -> this
}

fun GraphQLType.name(): String? = (this as? GraphQLNamedType)?.name
fun GraphQLType.requiredName(): String = requireNotNull(name()) { "name is required but cannot be determined for " + this.javaClass }

fun GraphQLType.isList() = this is GraphQLList || (this is GraphQLNonNull && this.wrappedType is GraphQLList)
fun GraphQLType.isNeo4jType() = this.innerName().startsWith("_Neo4j")
fun GraphQLType.isNeo4jTemporalType() = NEO4j_TEMPORAL_TYPES.contains(this.innerName())

fun TypeDefinition<*>.isNeo4jSpatialType() = this.name.startsWith("_Neo4jPoint")

fun GraphQLFieldDefinition.isNeo4jType(): Boolean = this.type.isNeo4jType()
fun GraphQLFieldDefinition.isNeo4jTemporalType(): Boolean = this.type.isNeo4jTemporalType()

fun GraphQLFieldDefinition.isRelationship() = !type.isNeo4jType() && this.type.inner().let { it is GraphQLFieldsContainer }

fun GraphQLFieldsContainer.isRelationType() = (this as? GraphQLDirectiveContainer)?.getDirective(DirectiveConstants.RELATION) != null
fun GraphQLFieldsContainer.relationshipFor(name: String): RelationshipInfo<GraphQLFieldsContainer>? {
    val field = getRelevantFieldDefinition(name)
            ?: throw IllegalArgumentException("$name is not defined on ${this.name}")
    val fieldObjectType = field.type.inner() as? GraphQLImplementingType ?: return null

    val (relDirective, inverse) = if (isRelationType()) {
        val typeName = this.name
        (this as? GraphQLDirectiveContainer)
            ?.getDirective(DirectiveConstants.RELATION)?.let {
                // do inverse mapping, if the current type  is the `to` mapping of the relation
                it to (fieldObjectType.getRelevantFieldDefinition(it.getArgument(RELATION_TO, null as String?))?.name == typeName)
            }
                ?: throw IllegalStateException("Type ${this.name} needs an @relation directive")
    } else {
        (fieldObjectType as? GraphQLDirectiveContainer)
            ?.getDirective(DirectiveConstants.RELATION)?.let { it to true }
                ?: field.getDirective(DirectiveConstants.RELATION)?.let { it to false }
                ?: throw IllegalStateException("Field $field needs an @relation directive")
    }

    val relInfo = RelationshipInfo.create(fieldObjectType, relDirective)

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
            ?.getArgument(RELATION_NAME)?.argumentValue?.value?.toJavaValue()?.toString()
                ?: this.name
    else -> name
}

fun GraphQLFieldsContainer.relationship(): RelationshipInfo<GraphQLFieldsContainer>? = RelationshipInfo.create(this)

fun GraphQLType.ref(): GraphQLType = when (this) {
    is GraphQLNonNull -> GraphQLNonNull(this.wrappedType.ref())
    is GraphQLList -> GraphQLList(this.wrappedType.ref())
    is GraphQLScalarType -> this
    is GraphQLEnumType -> this
    is GraphQLTypeReference -> this
    else -> GraphQLTypeReference(name())
}

fun Field.aliasOrName(): String = (this.alias ?: this.name)
fun SelectedField.aliasOrName(): String = (this.alias ?: this.name)
fun SelectedField.contextualize(variable: String) = variable + this.aliasOrName().capitalize()
fun SelectedField.contextualize(variable: SymbolicName) = variable.value + this.aliasOrName().capitalize()

fun GraphQLType.innerName(): String = inner().name()
        ?: throw IllegalStateException("inner name cannot be retrieved for " + this.javaClass)

fun GraphQLFieldDefinition.propertyName() = getDirectiveArgument(PROPERTY, PROPERTY_NAME, this.name)!!

fun GraphQLFieldDefinition.dynamicPrefix(): String? = getDirectiveArgument(DYNAMIC, DYNAMIC_PREFIX, null as String?)
fun GraphQLType.getInnerFieldsContainer() = inner() as? GraphQLFieldsContainer
        ?: throw IllegalArgumentException("${this.innerName()} is neither an object nor an interface")

fun <T> GraphQLDirectiveContainer.getDirectiveArgument(directiveName: String, argumentName: String, defaultValue: T?): T? =
        getDirective(directiveName)?.getArgument(argumentName, defaultValue) ?: defaultValue

@Suppress("UNCHECKED_CAST")
fun <T> DirectivesContainer<*>.getDirectiveArgument(typeRegistry: TypeDefinitionRegistry, directiveName: String, argumentName: String, defaultValue: T? = null): T? {
    return (getDirective(directiveName) ?: return defaultValue)
        .getArgument(argumentName)?.value?.toJavaValue() as T?
            ?: typeRegistry.getDirectiveDefinition(directiveName)
                ?.unwrap()
                ?.inputValueDefinitions
                ?.find { inputValueDefinition -> inputValueDefinition.name == argumentName }
                ?.defaultValue?.toJavaValue() as T?
            ?: defaultValue
}

fun DirectivesContainer<*>.getDirective(name: String): Directive? = directives.firstOrNull { it.name == name }

@Suppress("UNCHECKED_CAST")
fun <T> DirectivesContainer<*>.getMandatoryDirectiveArgument(typeRegistry: TypeDefinitionRegistry, directiveName: String, argumentName: String, defaultValue: T? = null): T =
        getDirectiveArgument(typeRegistry, directiveName, argumentName, defaultValue)
                ?: throw IllegalStateException("No default value for @${directiveName}::$argumentName")

fun <T> GraphQLDirective.getMandatoryArgument(argumentName: String, defaultValue: T? = null): T = this.getArgument(argumentName, defaultValue)
        ?: throw IllegalStateException(argumentName + " is required for @${this.name}")

fun <T> GraphQLDirective.getArgument(argumentName: String, defaultValue: T? = null): T? {
    val argument = getArgument(argumentName)
    @Suppress("UNCHECKED_CAST")
    return when {
        argument.argumentValue.isSet && argument.argumentValue.value != null -> argument.argumentValue.value?.toJavaValue() as T?
        argument.argumentDefaultValue.isSet -> argument.argumentDefaultValue.value?.toJavaValue() as T?
        else -> defaultValue ?: throw IllegalStateException("No default value for @${this.name}::$argumentName")
    }
}

fun GraphQLFieldDefinition.cypherDirective(): CypherDirective? = getDirective(CYPHER)?.let {
    val originalStatement = it.getMandatoryArgument<String>(CYPHER_STATEMENT)
    // Arguments on the field are passed to the Cypher statement and can be used by name.
    // They must not be prefixed by $ since they are no longer parameters. Just use the same name as the fields' argument.
    val rewrittenStatement = originalStatement.replace(Regex("\\\$([_a-zA-Z]\\w*)"), "$1")
    if (originalStatement != rewrittenStatement) {
        LoggerFactory.getLogger(CypherDirective::class.java)
            .warn("The field arguments used in the directives statement must not contain parameters. The statement was replaced. Please adjust your GraphQl Schema.\n\tGot        : {}\n\tReplaced by: {}\n\tField      : {} ({})",
                    originalStatement, rewrittenStatement, this.name, this.definition?.sourceLocation)
    }
    CypherDirective(rewrittenStatement, it.getMandatoryArgument(CYPHER_PASS_THROUGH, false))
}

data class CypherDirective(val statement: String, val passThrough: Boolean)

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
    is ObjectValue -> this.objectFields.associate { it.name to it.value.toJavaValue() }
    else -> throw IllegalStateException("Unhandled value $this")
}

fun GraphQLFieldDefinition.isID() = this.type.inner() == Scalars.GraphQLID
fun GraphQLFieldDefinition.isNativeId() = this.name == ProjectionBase.NATIVE_ID
fun GraphQLFieldDefinition.isIgnored() = getDirective(DirectiveConstants.IGNORE) != null
fun FieldDefinition.isIgnored(): Boolean = hasDirective(DirectiveConstants.IGNORE)

fun GraphQLFieldsContainer.getIdField() = this.getRelevantFieldDefinitions().find { it.isID() }

/**
 * Returns the field definitions which are not ignored
 */
fun GraphQLFieldsContainer.getRelevantFieldDefinitions() = this.fieldDefinitions.filterNot { it.isIgnored() }

/**
 * Returns the field definition if it is not ignored
 */
fun GraphQLFieldsContainer.getRelevantFieldDefinition(name: String?) = this.getFieldDefinition(name)?.takeIf { !it.isIgnored() }


fun InputObjectTypeDefinition.Builder.addFilterField(fieldName: String, isList: Boolean, filterType: String, description: Description? = null) {
    val wrappedType: Type<*> = when {
        isList -> ListType(TypeName(filterType))
        else -> TypeName(filterType)
    }
    val inputField = InputValueDefinition.newInputValueDefinition()
        .name(fieldName)
        .type(wrappedType)
    if (description != null) {
        inputField.description(description)
    }

    this.inputValueDefinition(inputField.build())
}

fun TypeDefinitionRegistry.queryTypeName() = this.getOperationType("query") ?: "Query"
fun TypeDefinitionRegistry.mutationTypeName() = this.getOperationType("mutation") ?: "Mutation"
fun TypeDefinitionRegistry.subscriptionTypeName() = this.getOperationType("subscription") ?: "Subscription"
fun TypeDefinitionRegistry.getOperationType(name: String) = this.schemaDefinition().unwrap()?.operationTypeDefinitions?.firstOrNull { it.name == name }?.typeName?.name

fun DataFetchingEnvironment.typeAsContainer() = this.fieldDefinition.type.inner() as? GraphQLFieldsContainer
        ?: throw IllegalStateException("expect type of field ${this.logField()} to be GraphQLFieldsContainer, but was ${this.fieldDefinition.type.name()}")

fun DataFetchingEnvironment.logField() = "${this.parentType.name()}.${this.fieldDefinition.name}"

val TypeInt = TypeName("Int")
val TypeFloat = TypeName("Float")
val TypeBoolean = TypeName("Boolean")
val TypeID = TypeName("ID")
