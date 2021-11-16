package org.neo4j.graphql

import graphql.GraphQLError

class InvalidQueryException(@Suppress("MemberVisibilityCanBePrivate") val error: GraphQLError) : RuntimeException(error.message)
