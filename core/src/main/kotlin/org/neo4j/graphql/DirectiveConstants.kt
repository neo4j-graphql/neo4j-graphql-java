package org.neo4j.graphql

import org.neo4j.graphql.domain.directives.*

object DirectiveConstants {

    const val AUTH = "auth"
    const val IGNORE = "ignore"
    const val READ_ONLY = "readonly"
    const val WRITE_ONLY = "writeonly"

    const val RELATION = "relation"
    const val RELATION_NAME = "name"
    const val RELATION_DIRECTION = "direction"
    const val RELATION_FROM = "from"
    const val RELATION_TO = "to"

    const val PROPERTY_NAME = "name"

    const val DYNAMIC = "dynamic"
    const val DYNAMIC_PREFIX = "prefix"

    val LIB_DIRECTIVES = setOf(
        AliasDirective.NAME,
        AuthenticationDirective.NAME,
        AuthorizationDirective.NAME,
        CoalesceDirective.NAME,
        CustomResolverDirective.NAME,
        CypherDirective.NAME,
        DefaultDirective.NAME,
        FilterableDirective.NAME,
        FulltextDirective.NAME,
        IdDirective.NAME,
        JwtClaimDirective.NAME,
        JwtDirective.NAME,
        LimitDirective.NAME,
        MutationDirective.NAME,
        NodeDirective.NAME,
        PluralDirective.NAME,
        PopulatedByDirective.NAME,
        PrivateDirective.NAME,
        QueryDirective.NAME,
        RelationshipDirective.NAME,
        RelationshipPropertiesDirective.NAME,
        RelayIdDirective.NAME,
        SelectableDirective.NAME,
        SettableDirective.NAME,
        SubscriptionDirective.NAME,
        TimestampDirective.NAME,
        UniqueDirective.NAME,
    )
}
