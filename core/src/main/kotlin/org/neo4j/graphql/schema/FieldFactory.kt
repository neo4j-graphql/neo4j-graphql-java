package org.neo4j.graphql.schema

import graphql.language.*
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.DirectiveConstants.ALIAS
import org.neo4j.graphql.DirectiveConstants.AUTH
import org.neo4j.graphql.DirectiveConstants.COALESCE
import org.neo4j.graphql.DirectiveConstants.CYPHER
import org.neo4j.graphql.DirectiveConstants.DEFAULT
import org.neo4j.graphql.DirectiveConstants.EXCLUDE
import org.neo4j.graphql.DirectiveConstants.ID
import org.neo4j.graphql.DirectiveConstants.IGNORE
import org.neo4j.graphql.DirectiveConstants.NODE
import org.neo4j.graphql.DirectiveConstants.PRIVATE
import org.neo4j.graphql.DirectiveConstants.READ_ONLY
import org.neo4j.graphql.DirectiveConstants.RELATIONSHIP
import org.neo4j.graphql.DirectiveConstants.RELATIONSHIP_PROPERTIES
import org.neo4j.graphql.DirectiveConstants.TIMESTAMP
import org.neo4j.graphql.DirectiveConstants.UNIQUE
import org.neo4j.graphql.DirectiveConstants.WRITE_ONLY
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.*
import org.neo4j.graphql.domain.directives.CypherDirective
import org.neo4j.graphql.domain.fields.*
import org.neo4j.graphql.domain.fields.ObjectField
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * A factory to create the internal representation of a [BaseField]
 */
object FieldFactory {

    fun createFields(
        obj: ImplementingTypeDefinition<*>,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        relationshipPropertiesFactory: (name: String) -> RelationshipProperties?,
        interfaceFactory: (name: String) -> Interface?,
    ): List<BaseField> {
        val objInterfaces = obj.implements
            .mapNotNull { typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it.name()) }

        val result = mutableListOf<BaseField>()

        obj.fieldDefinitions.mapNotNull { field ->

            // Create list of directives for this field. Field directives override interface field directives.
            val directives: MutableMap<String, Directive> = mutableMapOf()
            objInterfaces.mapNotNull { it.getField(field.name) }
                .forEach { interfaceField -> interfaceField.directives.associateByTo(directives) { it.name } }
            field.directives.associateByTo(directives) { it.name }

            checkDirectiveCombinations(directives.values)

            if (directives.containsKey(PRIVATE)) return@mapNotNull null

            val relationshipDirective =
                directives.remove(RELATIONSHIP)?.let { RelationshipDirective.create(it) }
            val cypherDirective = directives.remove(CYPHER)?.let { CypherDirective.create(it) }
            val typeMeta = TypeMeta.create(field)
            val authDirective = directives.remove(AUTH)?.let { AuthDirective.create(it) }
            val idDirective = directives.remove(ID)?.let { IdDirective.create(it) }
            val defaultDirective = directives.remove(DEFAULT)?.let { DefaultDirective.create(it) }
            val coalesceDirective = directives.remove(COALESCE)?.let { CoalesceDirective.create(it) }
            val timestampDirective =
                directives.remove(TIMESTAMP)?.let { TimestampDirective.create(it) }
            val aliasDirective = directives.remove(ALIAS)?.let { AliasDirective.create(it) }
            val unique = directives.remove(UNIQUE)
                ?.let { UniqueDirective.create(it) }
                ?: idDirective?.let { UniqueDirective("${obj.name}_${field.name}") }
            val ignoreDirective = directives.remove(IGNORE)
            val readonlyDirective = directives.remove(READ_ONLY)
            val writeonlyDirective = directives.remove(WRITE_ONLY)

            val fieldInterface = typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(typeMeta.type.name())
            val fieldUnion = typeDefinitionRegistry.getTypeByName<UnionTypeDefinition>(typeMeta.type.name())
            val fieldScalar = typeDefinitionRegistry.getTypeByName<ScalarTypeDefinition>(typeMeta.type.name())
                ?.takeUnless { ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.containsKey(it.name) }
            val fieldEnum = typeDefinitionRegistry.getTypeByName<EnumTypeDefinition>(typeMeta.type.name())
            val fieldObject = typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(typeMeta.type.name())

            val baseField: BaseField

            if (relationshipDirective != null) {
                val properties = if (relationshipDirective.properties != null) {
                    relationshipPropertiesFactory(relationshipDirective.properties)
                        ?: throw IllegalArgumentException("Cannot find interface specified in @$RELATIONSHIP of ${obj.name}.${field.name}")
                } else {
                    null
                }

                val interfaze = fieldInterface?.let { interfaceFactory(it.name) }
                var connectionPrefix = obj.name
                if (obj.implements.isNotEmpty()) {
                    obj.implements
                        .mapNotNull { typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it.name()) }
//                        .firstOrNull { it.getField(field.name) != null }
                        // TODO REVIEW Darrell
                        .firstOrNull { it.getField(field.name)?.type?.name() == field.type.name() }
                        ?.let {
                            connectionPrefix = it.name
                        }
                }

                baseField = RelationField(
                    field.name,
                    typeMeta,
                    interfaze,
                    union = fieldUnion?.memberTypes?.map { it.name() } ?: emptyList(),
                    relationshipDirective.type,
                    relationshipDirective.direction,
                    relationshipDirective.queryDirection,
                    properties,
                    connectionPrefix
                )


                val connectionTypeName = "${connectionPrefix}${baseField.fieldName.capitalize()}Connection"

                // TODO do we really need this field?
                val connectionField = ConnectionField(
                    fieldName = "${baseField.fieldName}Connection",
                    typeMeta = TypeMeta(
                        type = NonNullType(TypeName(connectionTypeName)),
                        whereType = TypeName(connectionTypeName + "Where")
                    ),
                    relationshipField = baseField
                ).apply {
                    this.dbPropertyName = aliasDirective?.property ?: field.name
                    this.otherDirectives = directives.values.toList()
                    this.arguments = field.inputValueDefinitions
                    this.auth = authDirective
                    this.readonly = readonlyDirective != null
                    this.writeonly = writeonlyDirective != null
                    this.unique = unique
                }
                result.add(connectionField)

                baseField.connectionField = connectionField

            } else if (cypherDirective != null) {
                baseField = CypherField(field.name, typeMeta, cypherDirective.statement)
            } else if (fieldScalar != null) {
                baseField = CustomScalarField(field.name, typeMeta)
            } else if (fieldEnum != null) {
                baseField = CustomEnumField(field.name, typeMeta)
            } else if (fieldUnion != null) {
                val nodes = fieldUnion.memberTypes.map { it.name() }
                baseField = UnionField(field.name, typeMeta, nodes)
            } else if (fieldInterface != null) {
                val implementations = fieldInterface.implements.mapNotNull { it.name() }
                baseField = InterfaceField(field.name, typeMeta, implementations)
            } else if (fieldObject != null) {
                baseField = ObjectField(field.name, typeMeta)
            } else if (ignoreDirective != null) {
                baseField = IgnoredField(field.name, typeMeta)
            } else if (Constants.TEMPORAL_TYPES.contains(typeMeta.type.name())) {
                baseField = TemporalField(field.name, typeMeta)

                if (timestampDirective != null) {
                    if (typeMeta.type.isList()) {
                        throw IllegalArgumentException("cannot auto-generate an array")
                    }
                    if (!Constants.ZONED_TIME_TYPES.contains(typeMeta.type.name())) {
                        throw  IllegalArgumentException("Cannot timestamp temporal fields lacking time zone information")
                    }
                    baseField.timestamps = timestampDirective.operations
                }

                if (defaultDirective != null) {
                    val value = defaultDirective.value.toJavaValue()
                    // todo add validation logic to field
//                        if () {
//                            throw  IllegalArgumentException("Default value for ${obj.name}.${field.name} is not a valid DateTime")
//                        }

                    baseField.defaultValue = defaultDirective.value
                }
            } else if (Constants.POINT_TYPES.contains(typeMeta.type.name())) {
                baseField = PointField(field.name, typeMeta)
            } else {
                baseField = PrimitiveField(field.name, typeMeta)

                if (idDirective != null) {
                    if (idDirective.autogenerate) {
                        if (typeMeta.type.name() != "ID") {
                            throw IllegalArgumentException("cannot auto-generate a non ID field")
                        }
                        if (typeMeta.type.isList()) {
                            throw IllegalArgumentException("cannot auto-generate an array")
                        }
                        baseField.autogenerate = true
                    }
                }

                if (defaultDirective != null) {
                    val value = defaultDirective.value
                    fun <T : Value<*>> Value<*>.checkKind(clazz: KClass<T>): T = clazz.safeCast(this)
                        ?: throw IllegalArgumentException("Default value for ${obj.name}.${field.name} does not have matching type ${typeMeta.type.name()}")
                    baseField.defaultValue = when (typeMeta.type.name()) {
                        "ID", "String" -> value.checkKind(StringValue::class)
                        "Boolean" -> value.checkKind(BooleanValue::class)
                        "Int" -> value.checkKind(IntValue::class)
                        "Float" -> value.checkKind(FloatValue::class)
                        else -> throw IllegalArgumentException("@default directive can only be used on types: Int | Float | String | Boolean | ID")
                    }
                }

                if (coalesceDirective != null) {
                    val value = coalesceDirective.value
                    fun <T : Value<*>> Value<*>.checkKind(clazz: KClass<T>): T = clazz.safeCast(this)
                        ?: throw IllegalArgumentException("coalesce() value for ${obj.name}.${field.name} does not have matching type ${typeMeta.type.name()}")
                    baseField.coalesceValue = when (typeMeta.type.name()) {
                        "ID", "String" -> value.checkKind(StringValue::class)
                        "Boolean" -> value.checkKind(BooleanValue::class)
                        "Int" -> value.checkKind(IntValue::class)
                        "Float" -> value.checkKind(FloatValue::class)
                        else -> throw IllegalArgumentException("@coalesce directive can only be used on types: Int | Float | String | Boolean | ID")
                    }
                }


            }

            baseField.apply {
                this.dbPropertyName = aliasDirective?.property ?: field.name
                this.otherDirectives = directives.values.toList()
                this.arguments = field.inputValueDefinitions
                this.auth = authDirective
                this.description = field.description
                this.comments = field.comments
                this.readonly = readonlyDirective != null
                this.writeonly = writeonlyDirective != null
                this.unique = unique
            }
            result.add(baseField)
        }

        return result
    }

    private val invalidDirectiveCombinations = mapOf(
        ALIAS to setOf(CYPHER, IGNORE, RELATIONSHIP),
        AUTH to setOf(IGNORE),
        COALESCE to emptySet(),
        CYPHER to emptySet(),
        DEFAULT to emptySet(),
        ID to setOf(CYPHER, IGNORE, RELATIONSHIP, TIMESTAMP, UNIQUE),
        IGNORE to setOf(ALIAS, AUTH, ID, READ_ONLY, RELATIONSHIP),
        PRIVATE to emptySet(),
        READ_ONLY to setOf(CYPHER, IGNORE),
        RELATIONSHIP to setOf(ALIAS, COALESCE, CYPHER, DEFAULT, ID, IGNORE, READ_ONLY),
        TIMESTAMP to setOf(ID, UNIQUE),
        UNIQUE to setOf(CYPHER, ID, IGNORE, RELATIONSHIP, TIMESTAMP),
        WRITE_ONLY to setOf(CYPHER, IGNORE),
        // OBJECT
        NODE to emptySet(),

        // INTERFACE
        RELATIONSHIP_PROPERTIES to emptySet(),

        // OBJECT and INTERFACE
        EXCLUDE to emptySet(),
    )

    private fun checkDirectiveCombinations(directives: Collection<Directive>) = directives.forEach { directive ->
        // Will skip any custom directives
        invalidDirectiveCombinations[directive.name]?.let { invalidCombinations ->
            directives.find { invalidCombinations.contains(it.name) }?.let {
                throw IllegalArgumentException("`Directive @${directive.name} cannot be used in combination with @${it.name}")

            }
        }
    }
}
