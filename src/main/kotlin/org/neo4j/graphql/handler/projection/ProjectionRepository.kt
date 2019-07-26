package org.neo4j.graphql.handler.projection

class ProjectionRepository(private val handler: MutableMap<String, ProjectionHandler> = mutableMapOf()) {

    fun getHandler(name: String): ProjectionHandler? = handler[name]
    fun add(typeName: String, projectionHandler: ProjectionHandler) = handler.put(typeName, projectionHandler)
}
