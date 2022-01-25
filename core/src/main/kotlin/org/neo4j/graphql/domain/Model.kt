package org.neo4j.graphql.domain

import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.merge.TypeDefinitionRegistryMerger
import org.neo4j.graphql.schema.FieldFactory
import org.neo4j.graphql.schema.InterfaceFactory
import org.neo4j.graphql.schema.NodeFactory
import java.util.concurrent.ConcurrentHashMap

data class Model(
    val nodes: List<Node>,
    val relationship: List<RelationField>
) {
    companion object {
        fun createModel(typeDefinitionRegistry: TypeDefinitionRegistry) =
            ModelInitializer(typeDefinitionRegistry).createModel()
    }

    private class ModelInitializer(val typeDefinitionRegistry: TypeDefinitionRegistry) {
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
                )
            }
            val nodesByName = nodes.associateBy { it.name }
            val implementations = mutableMapOf<Interface, MutableList<Node>>()
            nodes.forEach { node ->
                node.relationFields.forEach { field ->
                    field.unionNodes = field.union.mapNotNull { nodesByName[it] }
                    field.node = nodesByName[field.typeMeta.type.name()]
                }
                node.interfaces.forEach {
                    implementations.computeIfAbsent(it) { mutableListOf() }.add(node)
                }
            }
            implementations.forEach { (interfaze, impls) ->
                interfaze.implementations = impls
                interfaze.relationFields.forEach { field ->
                    field.unionNodes = field.union.mapNotNull { nodesByName[it] }
                    field.node = nodesByName[field.typeMeta.type.name()]
                }
            }
            val relationships = nodes.flatMap { it.relationFields }

            return Model(nodes, relationships)
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
                )
            }
        }
    }
}
