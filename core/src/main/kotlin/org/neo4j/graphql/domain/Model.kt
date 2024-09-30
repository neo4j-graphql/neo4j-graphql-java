package org.neo4j.graphql.domain

import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.UnionTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.Annotations
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
            schemaConfig: SchemaConfig
        ) =
            ModelInitializer(typeDefinitionRegistry, schemaConfig).createModel()
    }

    private class ModelInitializer(
        val typeDefinitionRegistry: TypeDefinitionRegistry,
        val schemaConfig: SchemaConfig,
    ) {
        private val relationshipFields = mutableMapOf<String, RelationshipProperties?>()
        private val interfaces = ConcurrentHashMap<String, Interface?>()

        private val schema = Schema()

        init {
            TypeDefinitionRegistryMerger.mergeExtensions(typeDefinitionRegistry)

            schema.annotations = Annotations(
                typeDefinitionRegistry.schemaDefinition().unwrap()?.directives ?: emptyList(),
                typeDefinitionRegistry
            )
        }


        fun createModel(): Model {
            val queryTypeName = typeDefinitionRegistry.queryTypeName()
            val mutationTypeName = typeDefinitionRegistry.mutationTypeName()
            val subscriptionTypeName = typeDefinitionRegistry.subscriptionTypeName()


            val reservedTypes =
                setOf(queryTypeName, mutationTypeName, subscriptionTypeName)

            val objectNodes = typeDefinitionRegistry.getTypes(ObjectTypeDefinition::class.java)
                .filterNot { reservedTypes.contains(it.name) }


            val nodes = objectNodes.mapNotNull { node ->
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

            val unions = parseUnions(nodesByName)

            nodes.forEach { node ->
                initRelations(node, nodesByName, unions)
                node.interfaces.forEach {
                    implementations.computeIfAbsent(it) { mutableListOf() }.add(node)
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
            typeDefinitionRegistry.getTypes(UnionTypeDefinition::class.java).forEach { unionDefinition ->
                val annotations = Annotations(unionDefinition.directives, typeDefinitionRegistry, unionDefinition.name)
                unionDefinition.memberTypes
                    .mapNotNull { nodesByName[it.name()] }
                    .sortedBy { it.name }
                    .map { it.name to it }
                    .takeIf { it.isNotEmpty() }
                    ?.let { Union(unionDefinition.name, it.toMap(), annotations) }
                    ?.let { unions[unionDefinition.name] = it }
            }
            return unions
        }

        private fun initRelations(
            type: ImplementingType,
            nodesByName: Map<String, Node>,
            unions: Map<String, Union>
        ) {
            type.relationBaseFields.forEach { field ->
                val nodeName = field.type.name()
                field.target = unions[nodeName] ?: nodesByName[nodeName]
                        ?: getOrCreateInterface(nodeName)
                        ?: error("Unknown type $nodeName")
            }
        }

        private fun getOrCreateRelationshipProperties(name: String): RelationshipProperties? {
            return relationshipFields.computeIfAbsent(name) {
                val relationship =
                    typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(it) ?: return@computeIfAbsent null

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
