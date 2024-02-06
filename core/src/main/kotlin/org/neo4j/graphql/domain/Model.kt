package org.neo4j.graphql.domain

import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.UnionTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.domain.directives.JwtDirective
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.merge.TypeDefinitionRegistryMerger
import java.util.concurrent.ConcurrentHashMap

data class Model(
    val nodes: Collection<Node>,
    val relationship: Collection<RelationField>,
    val interfaces: Collection<Interface>,
    val unions: Collection<Union>
) {

    val entities get() = nodes + interfaces + unions

    companion object {
        fun createModel(
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry,
            schemaConfig: SchemaConfig
        ) =
            ModelInitializer(typeDefinitionRegistry, neo4jTypeDefinitionRegistry, schemaConfig).createModel()
    }

    private class ModelInitializer(
        val typeDefinitionRegistry: TypeDefinitionRegistry,
        neo4jTypeDefinitionRegistry: TypeDefinitionRegistry,
        val schemaConfig: SchemaConfig,
    ) {
        private val relationshipFields = mutableMapOf<String, RelationshipProperties?>()
        private val interfaces = ConcurrentHashMap<String, Interface?>()

        val jwtShape: Node?

        private val schema = Schema()

        init {
            TypeDefinitionRegistryMerger.mergeExtensions(typeDefinitionRegistry)

            val jwtDefinitions = typeDefinitionRegistry.getTypes(ObjectTypeDefinition::class.java)
                .filter { it.getDirectives(JwtDirective.NAME).isNotEmpty() }
                .onEach { typeDefinitionRegistry.remove(it) }

            jwtShape =
                neo4jTypeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(Constants.Types.JWTStandard.name())
                    ?.transform { builder ->
                        jwtDefinitions.forEach { jwtDefinition ->
                            jwtDefinition.fieldDefinitions?.forEach { field ->
                                builder.fieldDefinition(field)
                            }
                        }
                    }
                    ?.let {
                        NodeFactory.createNode(
                            it,
                            typeDefinitionRegistry,
                            this::getOrCreateRelationshipProperties,
                            this::getOrCreateInterface,
                            schemaConfig,
                            jwtShape = null,
                            schema
                        )
                    }

            schema.annotations =
                Annotations(typeDefinitionRegistry.schemaDefinition().unwrap()?.directives ?: emptyList(), jwtShape)
        }


        fun createModel(): Model {
            val queryTypeName = typeDefinitionRegistry.queryTypeName()
            val mutationTypeName = typeDefinitionRegistry.mutationTypeName()
            val subscriptionTypeName = typeDefinitionRegistry.subscriptionTypeName()


            val reservedTypes =
                setOf(queryTypeName, mutationTypeName, subscriptionTypeName)

            val objectNodes = typeDefinitionRegistry.getTypes(ObjectTypeDefinition::class.java)
                .filterNot { reservedTypes.contains(it.name) }


            val nodes = objectNodes.map { node ->
                NodeFactory.createNode(
                    node,
                    typeDefinitionRegistry,
                    this::getOrCreateRelationshipProperties,
                    this::getOrCreateInterface,
                    schemaConfig,
                    jwtShape,
                    schema
                )
            }
            val nodesByName = nodes.associateBy { it.name }
            val implementations = mutableMapOf<Interface, MutableList<Node>>()

            val unions = parseUnions(nodesByName)

            nodes.forEach { node ->
                initRelations(node, nodesByName, unions)
                node.interfaces.forEach {
                    implementations.computeIfAbsent(it) { mutableListOf() }.add(node)
                }
                node.cypherFields.forEach { field ->
                    field.node = nodesByName[field.typeMeta.type.name()]
                    field.union = unions[field.typeMeta.type.name()]
                }
            }

            implementations.forEach { (interfaze, impls) ->
                interfaze.implementations = impls.sortedBy { it.name }.map { it.name to it }.toMap()
                initRelations(interfaze, nodesByName, unions)
            }
            val relationships = nodes.flatMap { it.relationFields }

            return Model(nodes, relationships, implementations.keys, unions.values)
        }

        private fun parseUnions(nodesByName: Map<String, Node>): Map<String, Union> {
            val unions = mutableMapOf<String, Union>()
            typeDefinitionRegistry.getTypes(UnionTypeDefinition::class.java).forEach { unionDefinion ->
                val annotations = Annotations(unionDefinion.directives, jwtShape)
                unionDefinion.memberTypes
                    .mapNotNull { nodesByName[it.name()] }
                    .sortedBy { it.name }
                    .map { it.name to it }
                    .takeIf { it.isNotEmpty() }
                    ?.let { Union(unionDefinion.name, it.toMap(), annotations, schema) }
                    ?.let { unions[unionDefinion.name] = it }
            }
            return unions
        }

        private fun initRelations(
            type: ImplementingType,
            nodesByName: Map<String, Node>,
            unions: Map<String, Union>
        ) {
            type.relationFields.forEach { field ->
                field.target = unions[field.typeMeta.type.name()]
                    ?: nodesByName[field.typeMeta.type.name()]
                            ?: getOrCreateInterface(field.typeMeta.type.name())
                            ?: error("Unknown type ${field.typeMeta.type.name()}")
            }
        }

        private fun getOrCreateRelationshipProperties(name: String): RelationshipProperties? {
            return relationshipFields.computeIfAbsent(name) {
                val relationship =
                    typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it) ?: return@computeIfAbsent null

                if (relationship.directives.find { directive -> directive.name == DirectiveConstants.AUTH } != null) {
                    throw IllegalArgumentException("Cannot have @auth directive on relationship properties interface")
                }

                relationship.fieldDefinitions?.forEach { field ->
                    Constants.RESERVED_INTERFACE_FIELDS[field.name]?.let { message ->
                        throw IllegalArgumentException(
                            message
                        )
                    }
                    field.directives.forEach { directive ->
                        if (Constants.FORBIDDEN_RELATIONSHIP_PROPERTY_DIRECTIVES.contains(directive.name)) {
                            throw IllegalArgumentException("Cannot have @${directive.name} directive on relationship property")
                        }
                    }
                }

                val fields = FieldFactory.createFields(
                    relationship,
                    typeDefinitionRegistry,
                    this::getOrCreateRelationshipProperties,
                    this::getOrCreateInterface,
                    schemaConfig,
                ).onEach { field ->
                    if (field !is ScalarField) {
                        throw IllegalStateException("Field $name.${field.fieldName} is expected to be of scalar type")
                    }
                }
                    .filterIsInstance<ScalarField>()
                RelationshipProperties(name, fields)
            }
        }

        private fun getOrCreateInterface(name: String): Interface? {
            return interfaces.computeIfAbsent(name) {
                val interfaze =
                    typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it) ?: return@computeIfAbsent null
                return@computeIfAbsent InterfaceFactory.createInterface(
                    interfaze,
                    typeDefinitionRegistry,
                    ::getOrCreateRelationshipProperties,
                    ::getOrCreateInterface,
                    schemaConfig,
                    jwtShape,
                    schema
                )
            }
        }
    }
}
