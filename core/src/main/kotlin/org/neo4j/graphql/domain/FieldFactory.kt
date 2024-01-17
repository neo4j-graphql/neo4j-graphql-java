package org.neo4j.graphql.domain

import graphql.language.*
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.domain.directives.PrivateDirective
import org.neo4j.graphql.domain.directives.RelationshipDirective
import org.neo4j.graphql.domain.fields.*
import org.neo4j.graphql.domain.fields.ObjectField
import org.slf4j.LoggerFactory
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
        schemaConfig: SchemaConfig,
    ): List<BaseField> {
        val objInterfaces = obj.implements
            .mapNotNull { typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it.name()) }

        val result = mutableListOf<BaseField>()

        obj.fieldDefinitions.forEach { field ->

            // Create list of directives for this field. Field directives override interface field directives.
            val directives: MutableMap<String, Directive> = mutableMapOf()
            objInterfaces.mapNotNull { it.getField(field.name) }
                .forEach { interfaceField -> interfaceField.directives.associateByTo(directives) { it.name } }
            field.directives.associateByTo(directives) { it.name }

            if (directives.containsKey(PrivateDirective.NAME)) return@forEach

            val annotations = Annotations(directives)
            val typeMeta = TypeMeta.create(field)

            val fieldInterface = typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(typeMeta.type.name())
            val fieldUnion = typeDefinitionRegistry.getTypeByName<UnionTypeDefinition>(typeMeta.type.name())
            val fieldScalar = typeDefinitionRegistry.getTypeByName<ScalarTypeDefinition>(typeMeta.type.name())
                ?.takeUnless { ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.containsKey(it.name) }
            val fieldEnum = typeDefinitionRegistry.getTypeByName<EnumTypeDefinition>(typeMeta.type.name())
            val fieldObject = typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(typeMeta.type.name())

            val baseField: BaseField

            if (annotations.relationship != null) {
                val properties = if (annotations.relationship.properties != null) {
                    relationshipPropertiesFactory(annotations.relationship.properties)
                        ?: throw IllegalArgumentException("Cannot find interface specified in @${RelationshipDirective.NAME} of ${obj.name}.${field.name}")
                } else {
                    null
                }

                var connectionPrefix = obj.name
                if (obj.implements.isNotEmpty()) {
                    val interfacedDefs =
                        obj.implements.mapNotNull { typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it.name()) }
                    val firstInterface = interfacedDefs.firstOrNull()
                    if (firstInterface != null && firstInterface.getField(field.name) != null) {
                        // TODO Darrell why only the 1st interface?
                        connectionPrefix = firstInterface.name
                    }
                }

                baseField = RelationField(
                    field.name,
                    typeMeta,
                    annotations,
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
                    annotations,
                    relationshipField = baseField
                ).apply {
                    this.arguments = field.inputValueDefinitions
                }
                result.add(connectionField)

                baseField.connectionField = connectionField

            } else if (annotations.cypher != null) {
                val originalStatement = annotations.cypher.statement
                // Arguments on the field are passed to the Cypher statement and can be used by name.
                // They must not be prefixed by $ since they are no longer parameters. Just use the same name as the fields' argument.
                val rewrittenStatement = originalStatement.replace(Regex("\\\$([_a-zA-Z]\\w*)"), "$1")
                if (originalStatement != rewrittenStatement) {
                    LoggerFactory.getLogger(FieldFactory::class.java)
                        .warn(
                            """
                            The field arguments used in the directives statement must not contain parameters. The statement was replaced. Please adjust your GraphQl Schema.
                                Got        : {}
                                Replaced by: {}
                                Field      : {}.{} ({})
                            """.trimIndent(),
                            originalStatement, rewrittenStatement, obj.name, field.name, field.sourceLocation
                        )
                }
                baseField = CypherField(
                    field.name,
                    typeMeta,
                    annotations,
                    rewrittenStatement,
                )

            } else if (annotations.customResolver != null) {
                baseField = ComputedField(field.name, typeMeta, annotations)
            } else if (fieldScalar != null) {
                baseField = CustomScalarField(field.name, typeMeta, annotations, schemaConfig)
            } else if (fieldEnum != null) {
                baseField = CustomEnumField(field.name, typeMeta, annotations, schemaConfig)
                if (annotations.coalesce != null) {
                    require(annotations.coalesce.value is EnumValue) { "@coalesce value on enum fields must be an enum value" }
                }
            } else if (fieldUnion != null) {
                val nodes = fieldUnion.memberTypes.map { it.name() }
                baseField = UnionField(field.name, typeMeta, annotations, nodes)
            } else if (fieldInterface != null) {
                val implementations = fieldInterface.implements.mapNotNull { it.name() }
                baseField = InterfaceField(field.name, typeMeta, annotations, implementations)
            } else if (fieldObject != null) {
                baseField = ObjectField(field.name, typeMeta, annotations)
            } else if (Constants.TEMPORAL_TYPES.contains(typeMeta.type.name())) {
                baseField = TemporalField(field.name, typeMeta, annotations, schemaConfig)

                if (annotations.timestamp != null) {
                    require(!typeMeta.type.isList()) { "cannot auto-generate an array" }
                    require(Constants.ZONED_TIME_TYPES.contains(typeMeta.type.name())) { "Cannot timestamp temporal fields lacking time zone information" }
                }

                if (annotations.default != null) {
                    val value = annotations.default.value.toJavaValue()
                    // todo add validation logic to field
//                        if () {
//                            throw  IllegalArgumentException("Default value for ${obj.name}.${field.name} is not a valid DateTime")
//                        }
                }
            } else if (Constants.POINT_TYPES.contains(typeMeta.type.name())) {
                val type = when (typeMeta.type.name()) {
                    Constants.POINT_TYPE -> PointField.Type.GEOGRAPHIC
                    Constants.CARTESIAN_POINT_TYPE -> PointField.Type.CARTESIAN
                    else -> error("unsupported point type " + typeMeta.type.name())
                }
                baseField = PointField(field.name, typeMeta, type, annotations, schemaConfig)
            } else {
                baseField = PrimitiveField(field.name, typeMeta, annotations, schemaConfig)

                // TODO add validation
//                if (idDirective != null) {
//                    if (idDirective.autogenerate) {
//                        if (typeMeta.type.name() != "ID") {
//                            throw IllegalArgumentException("cannot auto-generate a non ID field")
//                        }
//                        if (typeMeta.type.isList()) {
//                            throw IllegalArgumentException("cannot auto-generate an array")
//                        }
//                        baseField.autogenerate = true
//                    }
//                }

                if (annotations.default != null) {
                    val value = annotations.default.value
                    fun <T : Value<*>> Value<*>.checkKind(clazz: KClass<T>): T = clazz.safeCast(this)
                        ?: throw IllegalArgumentException("Default value for ${obj.name}.${field.name} does not have matching type ${typeMeta.type.name()}")
                    when (typeMeta.type.name()) {
                        Constants.ID, Constants.STRING -> value.checkKind(StringValue::class)
                        Constants.BOOLEAN -> value.checkKind(BooleanValue::class)
                        Constants.INT -> value.checkKind(IntValue::class)
                        Constants.FLOAT -> value.checkKind(FloatValue::class)
                        else -> throw IllegalArgumentException("@default directive can only be used on types: Int | Float | String | Boolean | ID")
                    }
                }

                if (annotations.coalesce != null) {
                    val value = annotations.coalesce.value
                    fun <T : Value<*>> Value<*>.checkKind(clazz: KClass<T>): T = clazz.safeCast(this)
                        ?: throw IllegalArgumentException("coalesce() value for ${obj.name}.${field.name} does not have matching type ${typeMeta.type.name()}")
                    when (typeMeta.type.name()) {
                        Constants.ID, Constants.STRING -> value.checkKind(StringValue::class)
                        Constants.BOOLEAN -> value.checkKind(BooleanValue::class)
                        Constants.INT -> value.checkKind(IntValue::class)
                        Constants.FLOAT -> value.checkKind(FloatValue::class)
                        else -> throw IllegalArgumentException("@coalesce directive can only be used on types: Int | Float | String | Boolean | ID")
                    }
                }


            }

            baseField.apply {
                this.arguments = field.inputValueDefinitions
                this.description = field.description
                this.comments = field.comments
            }
            result.add(baseField)
        }

        return result
    }
}
