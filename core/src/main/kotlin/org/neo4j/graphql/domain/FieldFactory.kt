package org.neo4j.graphql.domain

import graphql.language.*
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.domain.directives.FilterableDirective
import org.neo4j.graphql.domain.directives.RelationshipDirective
import org.neo4j.graphql.domain.directives.SelectableDirective
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
        schemaConfig: SchemaConfig
    ): List<BaseField> {
        val result = mutableListOf<BaseField>()

        obj.fieldDefinitions.forEach { field ->

            val annotations = Annotations(field.directives, typeDefinitionRegistry, obj.name)
            if (annotations.private != null) return@forEach
            val typeName = field.type.name()

            val fieldInterface = typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(typeName)
            val fieldUnion = typeDefinitionRegistry.getTypeByName<UnionTypeDefinition>(typeName)
            val fieldScalar = typeDefinitionRegistry.getTypeByName<ScalarTypeDefinition>(typeName)
                ?.takeUnless { ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.containsKey(it.name) }
            val fieldEnum = typeDefinitionRegistry.getTypeByName<EnumTypeDefinition>(typeName)
            val fieldObject = typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(typeName)

            val baseField: BaseField

            if (annotations.relationship != null) {

                var connectionPrefix = obj.name
                if (obj.implements.isNotEmpty()) {
                    val firstInterface = obj.implements
                        .firstNotNullOfOrNull { typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it.name()) }
                    if (firstInterface?.getField(field.name) != null) {
                        // TODO Darrell why only the 1st interface?
                        connectionPrefix = firstInterface.name
                    }
                }

                baseField = run {
                    val properties = if (annotations.relationship.properties != null) {
                        relationshipPropertiesFactory(annotations.relationship.properties)
                            ?: throw IllegalArgumentException("Cannot find type specified in @${RelationshipDirective.NAME} of ${obj.name}.${field.name}")
                    } else {
                        null
                    }
                    RelationField(
                        field.name,
                        field.type,
                        annotations,
                        properties
                    )
                }

                val connectionTypeName = "${connectionPrefix}${baseField.fieldName.capitalize()}Connection"

                // TODO do we really need this field?
                val connectionField = ConnectionField(
                    fieldName = "${baseField.fieldName}Connection",
                    type = TypeName(connectionTypeName).NonNull,
                    Annotations(
                        field.directives.filter {
                            it.name == SelectableDirective.NAME ||
                                    it.name == FilterableDirective.NAME ||
                                    it.name == "deprecated"
                        },
                        typeDefinitionRegistry,
                        obj.name
                    ),
                    relationshipField = baseField
                ).apply {
                    this.arguments = field.inputValueDefinitions
                }
                result.add(connectionField)

                baseField.connectionField = connectionField


            } else if (annotations.customResolver != null) {
                baseField = ComputedField(field.name, field.type, annotations)
            } else if (fieldScalar != null) {
                baseField = CustomScalarField(field.name, field.type, annotations, schemaConfig)
            } else if (fieldEnum != null) {
                baseField = CustomEnumField(field.name, field.type, annotations, schemaConfig)
                if (annotations.coalesce != null) {
                    if (field.type.isList()) {
                        (annotations.coalesce.value as ArrayValue).values
                    } else {
                        listOf(annotations.coalesce.value)
                    }
                        .forEach {
                            require(it is EnumValue) { "@coalesce value on enum fields must be an enum value" }
                        }
                }
            } else if (fieldUnion != null) {
                val nodes = fieldUnion.memberTypes.map { it.name() }
                baseField = UnionField(field.name, field.type, annotations, nodes)
            } else if (fieldInterface != null) {
                val implementations = fieldInterface.implements.mapNotNull { it.name() }
                baseField = InterfaceField(field.name, field.type, annotations, implementations)
            } else if (fieldObject != null) {
                baseField = ObjectField(field.name, field.type, annotations)
            } else if (Constants.TEMPORAL_TYPES.contains(typeName)) {
                baseField = TemporalField(field.name, field.type, annotations, schemaConfig)
            } else if (Constants.POINT_TYPES.contains(typeName)) {
                val coordinateType = when (typeName) {
                    Constants.POINT_TYPE -> PointField.CoordinateType.GEOGRAPHIC
                    Constants.CARTESIAN_POINT_TYPE -> PointField.CoordinateType.CARTESIAN
                    else -> error("unsupported point type $typeName")
                }
                baseField = PointField(field.name, field.type, coordinateType, annotations, schemaConfig)
            } else {
                baseField = PrimitiveField(field.name, field.type, annotations, schemaConfig)

                if (annotations.default != null) {
                    val value = annotations.default.value
                    fun <T : Value<*>> Value<*>.checkKind(clazz: KClass<T>): T = clazz.safeCast(this)
                        ?: throw IllegalArgumentException("Default value for ${obj.name}.${field.name} does not have matching type $typeName")
                    when (typeName) {
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
                        ?: throw IllegalArgumentException("coalesce() value for ${obj.name}.${field.name} does not have matching type $typeName")
                    when (typeName) {
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
