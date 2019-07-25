package org.neo4j.graphql.handler

class ProjectionRepository(private val handler: MutableMap<String, ProjectionHandler> = mutableMapOf()) {

    fun getHandler(name: String): ProjectionHandler? = handler.get(name)
}
