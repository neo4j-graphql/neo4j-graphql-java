package org.neo4j.graphql.domain

import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.merge.TypeDefinitionRegistryMerger
import java.util.concurrent.ConcurrentHashMap

data class Model(
    val nodes: Collection<Node>,
    val relationship: Collection<RelationField>,
    val interfaces: Collection<Interface>
) {
    companion object {
        fun createModel(typeDefinitionRegistry: TypeDefinitionRegistry, schemaConfig: SchemaConfig) =
            ModelInitializer(typeDefinitionRegistry, schemaConfig).createModel()
    }

    private class ModelInitializer(
        val typeDefinitionRegistry: TypeDefinitionRegistry,
        val schemaConfig: SchemaConfig,
    ) {
        private val relationshipFields = mutableMapOf<String, RelationshipProperties?>()
        private val interfaces = ConcurrentHashMap<String, Interface?>()

        fun createModel(): Model {
            TypeDefinitionRegistryMerger.mergeExtensions(typeDefinitionRegistry)

            val queryTypeName = typeDefinitionRegistry.queryTypeName()
            val mutationTypeName = typeDefinitionRegistry.mutationTypeName()
            val subscriptionTypeName = typeDefinitionRegistry.subscriptionTypeName()
            val reservedTypes = setOf(queryTypeName, mutationTypeName, subscriptionTypeName)

            val objectNodes = typeDefinitionRegistry.getTypes(ObjectTypeDefinition::class.java)
                .filterNot { reservedTypes.contains(it.name) }

            val nodes = objectNodes.map { node ->
                NodeFactory.createNode(
                    node,
                    typeDefinitionRegistry,
                    this::getOrCreateRelationshipProperties,
                    this::getOrCreateInterface,
                    schemaConfig,
                )
            }
            val nodesByName = nodes.associateBy { it.name }
            val implementations = mutableMapOf<Interface, MutableList<Node>>()
            nodes.forEach { node ->
                initRelations(node, nodesByName)
                node.interfaces.forEach {
                    implementations.computeIfAbsent(it) { mutableListOf() }.add(node)
                }
                node.cypherFields.forEach { it.node = nodesByName[it.typeMeta.type.name()] }
            }
            implementations.forEach { (interfaze, impls) ->
                interfaze.implementations = impls.sortedBy { it.name }.map { it.name to it }.toMap()
                initRelations(interfaze, nodesByName)
            }
            val relationships = nodes.flatMap { it.relationFields }

            return Model(nodes, relationships, implementations.keys)
        }

        private fun initRelations(type: ImplementingType, nodesByName: Map<String, Node>) {
            type.relationFields.forEach { field ->
                field.union = field.unionNodeNames
                    .mapNotNull { nodesByName[it] }
                    .sortedBy { it.name }
                    .map { it.name to it }
                    .takeIf { it.isNotEmpty() }
                    ?.let { Union(field.typeMeta.type.name(), it.toMap()) }
                field.node = nodesByName[field.typeMeta.type.name()]
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
                )
            }
        }
    }
}
