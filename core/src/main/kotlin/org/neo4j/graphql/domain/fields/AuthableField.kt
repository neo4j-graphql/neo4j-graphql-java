package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.directives.AuthDirective

interface AuthableField {
    var auth: AuthDirective?
}
