package org.neo4j.graphql.domain.dto

class DeleteInput(data: Map</* fieldName */ String, List<DeleteFieldInput>>): Map<String, List<DeleteFieldInput>> by data
