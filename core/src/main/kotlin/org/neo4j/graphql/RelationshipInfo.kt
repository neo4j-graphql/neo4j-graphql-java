package org.neo4j.graphql

import graphql.language.ImplementingTypeDefinition
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.cypherdsl.core.Node
import org.neo4j.cypherdsl.core.Relationship
import org.neo4j.cypherdsl.core.SymbolicName

data class RelationshipInfo<TYPE>(
        val type: TYPE,
        val typeName: String,
        val relType: String,
        val direction: RelationDirection,
        val startField: String,
        val endField: String
) {

    enum class RelationDirection {
        IN,
        OUT,
        BOTH;

        fun invert(): RelationDirection = when (this) {
            IN -> OUT
            OUT -> IN
            else -> this
        }

    }

    companion object {
        fun create(type: GraphQLFieldsContainer): RelationshipInfo<GraphQLFieldsContainer>? = (type as? GraphQLDirectiveContainer)
            ?.getDirective(DirectiveConstants.RELATION)
            ?.let { relDirective -> create(type, relDirective) }

        fun create(type: GraphQLFieldsContainer, relDirective: GraphQLDirective): RelationshipInfo<GraphQLFieldsContainer> {
            val relType = relDirective.getArgument(DirectiveConstants.RELATION_NAME, "")!!
            val direction = relDirective.getArgument<String>(DirectiveConstants.RELATION_DIRECTION, null)
                ?.let { RelationDirection.valueOf(it) }
                    ?: RelationDirection.OUT

            return RelationshipInfo(
                    type,
                    type.name,
                    relType,
                    direction,
                    relDirective.getMandatoryArgument(DirectiveConstants.RELATION_FROM),
                    relDirective.getMandatoryArgument(DirectiveConstants.RELATION_TO)
            )
        }

        fun create(type: ImplementingTypeDefinition<*>, registry: TypeDefinitionRegistry): RelationshipInfo<ImplementingTypeDefinition<*>>? {
            val relType = type.getDirectiveArgument<String>(registry, DirectiveConstants.RELATION, DirectiveConstants.RELATION_NAME)
                    ?: return null
            val startField = type.getMandatoryDirectiveArgument<String>(registry, DirectiveConstants.RELATION, DirectiveConstants.RELATION_FROM)
            val endField = type.getMandatoryDirectiveArgument<String>(registry, DirectiveConstants.RELATION, DirectiveConstants.RELATION_TO)
            val direction = type.getDirectiveArgument<String>(registry, DirectiveConstants.RELATION, DirectiveConstants.RELATION_DIRECTION)
                ?.let { RelationDirection.valueOf(it) }
                    ?: RelationDirection.OUT
            return RelationshipInfo(type, type.name, relType, direction, startField, endField)
        }
    }

    fun createRelation(start: Node, end: Node, addType: Boolean = true, variable: SymbolicName? = null): Relationship {
        val labels = if (addType) {
            arrayOf(this.relType)
        } else {
            emptyArray()
        }
        return when (this.direction) {
            RelationDirection.IN -> start.relationshipFrom(end, *labels)
            RelationDirection.OUT -> start.relationshipTo(end, *labels)
            RelationDirection.BOTH -> start.relationshipBetween(end, *labels)
        }
            .let { if (variable != null) it.named(variable) else it }
    }
}
