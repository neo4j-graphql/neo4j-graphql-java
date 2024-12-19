package org.neo4j.graphql.utils

import graphql.GraphQLError

class InvalidQueryException(@Suppress("MemberVisibilityCanBePrivate") val error: GraphQLError) :
    RuntimeException(error.message)
