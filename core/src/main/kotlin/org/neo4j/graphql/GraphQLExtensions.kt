package org.neo4j.graphql

import graphql.GraphQLContext
import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.graphql.DirectiveConstants.RELATION_NAME
import org.neo4j.graphql.domain.fields.BaseField
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty1

fun Type<*>.name(): String = this.inner().name

fun Type<*>.inner(): TypeName = when (this) {
    is ListType -> this.type.inner()
    is NonNullType -> this.type.inner()
    is TypeName -> this
    else -> throw IllegalStateException("Inner type is expected to hava a name")
}

fun Type<*>.isList(): Boolean = when (this) {
    is ListType -> true
    is NonNullType -> type.isList()
    else -> false
}

fun Type<*>.isRequired(): Boolean = when (this) {
    is NonNullType -> true
    else -> false
}

fun String.asRequiredType() = asType(true)
fun String.asType(required: Boolean = false) = TypeName(this).makeRequired(required)
fun String.wrapLike(type: Type<*>) = TypeName(this).wrapLike(type)
fun TypeName.wrapLike(type: Type<*>): Type<*> = when (type) {
    is ListType -> ListType(this.wrapLike(type.type))
    is NonNullType -> NonNullType(this.wrapLike(type.type))
    is TypeName -> this
    else -> throw IllegalStateException("Unknown type")
}

val Type<*>.NonNull
    get() = when (this) {
        is NonNullType -> this
        else -> NonNullType(this)
    }

val Type<*>.List
    get() = when (this) {
        is ListType -> this
        else -> ListType(this)
    }

fun String.wrapType(field: BaseField) = when {
    field.typeMeta.type.isList() -> ListType(this.asRequiredType())
    else -> this.asType()
}

fun TypeName.makeRequired(required: Boolean = true): Type<*> = when (required) {
    true -> NonNullType(this)
    else -> this
}

fun Type<*>.isListElementRequired(): Boolean = when (this) {
    is ListType -> type is NonNullType
    is NonNullType -> type.isListElementRequired()
    else -> false
}

fun GraphQLType.inner(): GraphQLType = when (this) {
    is GraphQLList -> this.wrappedType.inner()
    is GraphQLNonNull -> this.wrappedType.inner()
    else -> this
}

fun GraphQLType.name(): String? = (this as? GraphQLNamedType)?.name

fun GraphQLType.isList() = this is GraphQLList || (this is GraphQLNonNull && this.wrappedType is GraphQLList)

fun GraphQLFieldsContainer.isRelationType() =
    (this as? GraphQLDirectiveContainer)?.getDirective(DirectiveConstants.RELATION) != null


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

fun GraphQLType.innerName(): String = inner().name()
    ?: throw IllegalStateException("inner name cannot be retrieved for " + this.javaClass)


fun <T> GraphQLDirectiveContainer.getDirectiveArgument(
    directiveName: String,
    argumentName: String,
    defaultValue: T?
): T? =
    getDirective(directiveName)?.getArgument(argumentName, defaultValue) ?: defaultValue

@Suppress("UNCHECKED_CAST")
fun <T> DirectivesContainer<*>.getDirectiveArgument(
    typeRegistry: TypeDefinitionRegistry,
    directiveName: String,
    argumentName: String,
    defaultValue: T? = null
): T? {
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
fun <T> DirectivesContainer<*>.getMandatoryDirectiveArgument(
    typeRegistry: TypeDefinitionRegistry,
    directiveName: String,
    argumentName: String,
    defaultValue: T? = null
): T =
    getDirectiveArgument(typeRegistry, directiveName, argumentName, defaultValue)
        ?: throw IllegalStateException("No default value for @${directiveName}::$argumentName")

fun <T> GraphQLDirective.getMandatoryArgument(argumentName: String, defaultValue: T? = null): T =
    this.getArgument(argumentName, defaultValue)
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

fun FieldDefinition.isIgnored(): Boolean = hasDirective(DirectiveConstants.IGNORE)

fun TypeDefinitionRegistry.queryType() = this.getTypeByName<ObjectTypeDefinition>(this.queryTypeName())
fun TypeDefinitionRegistry.mutationType() = this.getTypeByName<ObjectTypeDefinition>(this.mutationTypeName())
fun TypeDefinitionRegistry.subscriptionType() = this.getTypeByName<ObjectTypeDefinition>(this.subscriptionTypeName())
fun TypeDefinitionRegistry.queryTypeName() = this.getOperationType("query") ?: "Query"
fun TypeDefinitionRegistry.mutationTypeName() = this.getOperationType("mutation") ?: "Mutation"
fun TypeDefinitionRegistry.subscriptionTypeName() = this.getOperationType("subscription") ?: "Subscription"
fun TypeDefinitionRegistry.getOperationType(name: String) =
    this.schemaDefinition().unwrap()?.operationTypeDefinitions?.firstOrNull { it.name == name }?.typeName?.name

fun DataFetchingEnvironment.typeAsContainer() = this.fieldDefinition.type.inner() as? GraphQLFieldsContainer
    ?: throw IllegalStateException("expect type of field ${this.logField()} to be GraphQLFieldsContainer, but was ${this.fieldDefinition.type.name()}")

fun DataFetchingEnvironment.logField() = "${this.parentType.name()}.${this.fieldDefinition.name}"
fun DataFetchingEnvironment.queryContext(): QueryContext =
    this.graphQlContext.get<QueryContext?>(QueryContext.KEY)
        ?: run {
            val queryContext = QueryContext()
            this.graphQlContext.put(QueryContext.KEY, queryContext)
            queryContext
        }

fun GraphQLContext.setQueryContext(ctx: QueryContext): GraphQLContext {
    this.put(QueryContext.KEY, ctx)
    return this
}


fun ObjectValue.get(name: String): Value<Value<*>>? = this.objectFields.firstOrNull { it.name == name }?.value

@Suppress("UNCHECKED_CAST")
fun <T> ObjectValue.map(prop: KMutableProperty0<T?>) {
    this.map(prop) { it.toJavaValue() as T? }
}

fun <T> ObjectValue.map(prop: KMutableProperty0<T>, transfomer: (v: Value<*>) -> T) {
    this.get(prop.name)?.let {
        prop.set(transfomer.invoke(it))
    }
}

@Suppress("UNCHECKED_CAST")
fun <T> Directive.map(prop: KMutableProperty0<T?>) {
    this.map(prop) { it.toJavaValue() as T? }
}

fun <T> Directive.map(prop: KMutableProperty0<T>, transfomer: (v: Value<*>) -> T) {
    this.getArgument(prop.name)?.let {
        prop.set(transfomer.invoke(it.value))
    }
}

fun Directive.validateName(expectedName: String) {
    if (name != expectedName) throw IllegalArgumentException("expected directive @${expectedName}")
}


fun <T : Any?> Directive.readArgument(prop: KProperty1<*, T>): T? = readArgument(prop) {
    @Suppress("UNCHECKED_CAST")
    it.toJavaValue() as T?
}

fun <T : Any?> Directive.readArgument(prop: KProperty1<*, T>, transformer: (v: Value<*>) -> T?): T? {
    return this.getArgument(prop.name)?.let { transformer.invoke(it.value) }
}

fun <T : Any?> ObjectValue.readField(prop: KProperty1<*, T>): T? = readField(prop) {
    @Suppress("UNCHECKED_CAST")
    it.toJavaValue() as T?
}

fun <T : Any?> ObjectValue.readField(prop: KProperty1<*, T>, transformer: (v: Value<*>) -> T?): T? {
    return this.get(prop.name)?.let { transformer.invoke(it) }
}

fun <T : Any?> Directive.readRequiredArgument(prop: KProperty1<*, T>): T = readRequiredArgument(prop) {
    @Suppress("UNCHECKED_CAST")
    it.toJavaValue() as T?
}

fun <T : Any?> Directive.readRequiredArgument(prop: KProperty1<*, T>, transformer: (v: Value<*>) -> T?): T =
    readArgument(prop, transformer)
        ?: throw IllegalArgumentException("@${this.name} is missing the required argument ${prop.name}")

fun <T> ObjectValue.get(prop: KProperty1<*, T>): T? = this.get(prop) {
    @Suppress("UNCHECKED_CAST")
    it.toJavaValue() as T?
}

fun <T> ObjectValue.get(prop: KProperty1<*, T>, transformer: (v: Value<*>) -> T): T? =
    this.get(prop.name)?.let { transformer.invoke(it) }


fun TypeDefinitionRegistry.getUnwrappedType(name: String?): TypeDefinition<TypeDefinition<*>>? =
    getType(name)?.unwrap()

inline fun <reified T : TypeDefinition<*>> TypeDefinitionRegistry.getTypeByName(name: String): T? =
    this.getType(name, T::class.java)?.unwrap()

fun ImplementingTypeDefinition<*>.getField(name: String): FieldDefinition? = fieldDefinitions.find { it.name == name }

fun TypeDefinitionRegistry.replace(definition: SDLDefinition<*>) {
    this.remove(definition)
    this.add(definition)
}

fun NodeDirectivesBuilder.addNonLibDirectives(directivesContainer: DirectivesContainer<*>) {
    this.directives(directivesContainer.directives.filterNot { DirectiveConstants.LIB_DIRECTIVES.contains(it.name) })
}

inline fun <reified T> T.asCypherLiteral() = Cypher.literalOf<T>(this)
