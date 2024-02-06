package org.neo4j.graphql.domain

import graphql.language.Comment
import graphql.language.Description
import org.neo4j.graphql.domain.directives.InterfaceAnnotations
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.naming.InterfaceNames

class Interface(
    name: String,
    description: Description? = null,
    comments: List<Comment> = emptyList(),
    fields: List<BaseField>,
    interfaces: List<Interface>,
    override val annotations: InterfaceAnnotations,
    schema: Schema,
) : ImplementingType(name, description, comments, fields, interfaces, annotations, schema), NodeResolver {

    var implementations: Map<String, Node> = emptyMap()

    override val namings = InterfaceNames(name, annotations)

    override fun getRequiredNode(name: String) = implementations[name]
        ?: throw IllegalArgumentException("unknown implementation $name for interface ${this.name}")

    override fun getNode(name: String) = implementations[name]

    override fun toString(): String {
        return "Interface('$name')"
    }
}
