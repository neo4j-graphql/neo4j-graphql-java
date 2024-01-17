package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import kotlin.reflect.KProperty1

class Annotations(
    directives: Map<String, Directive>,
) {

    constructor(directives: Collection<Directive>) : this(directives.associateBy { it.name })

    private val unhandledDirectives: MutableMap<String, Directive> = directives.toMutableMap()

    val otherDirectives get() = this.unhandledDirectives.values.toList()

    @Deprecated("Use @authentication and / or @authorization instead")
    val auth: AuthDirective? = parse(Annotations::auth, AuthDirective::create)

    val alias: AliasDirective? = parse(Annotations::alias, AliasDirective::create)

    val authentication: AuthenticationDirective? = parse(Annotations::authentication, AuthenticationDirective::create)

    val authorization: AuthorizationDirective? = parse(Annotations::authorization, AuthorizationDirective::create)

    val coalesce: CoalesceDirective? = parse(Annotations::coalesce, CoalesceDirective::create)

    val customResolver: CustomResolverDirective? = parse(Annotations::customResolver, CustomResolverDirective::create)

    val cypher: CypherDirective? = parse(Annotations::cypher, CypherDirective::create)

    val default: DefaultDirective? = parse(Annotations::default, DefaultDirective::create)

    val filterable: FilterableDirective? = parse(Annotations::filterable, FilterableDirective::create)

    val fulltext: FulltextDirective? = parse(Annotations::fulltext, FulltextDirective::create)

    val id: IdDirective? = parse(Annotations::id, IdDirective::create)

    val jwtClaim: JwtClaimDirective? = parse(Annotations::jwtClaim, JwtClaimDirective::create)

    val jwt: JwtDirective? = parse(Annotations::jwt, JwtDirective::create)

    val limit: LimitDirective? = parse(Annotations::limit, LimitDirective::create)

    val mutation: MutationDirective? = parse(Annotations::mutation, MutationDirective::create)

    val node: NodeDirective? = parse(Annotations::node, NodeDirective::create)

    val plural: PluralDirective? = parse(Annotations::plural, PluralDirective::create)

    val populatedBy: PopulatedByDirective? = parse(Annotations::populatedBy, PopulatedByDirective::create)

    val private: PrivateDirective? = parse(Annotations::private, PrivateDirective::create)

    val query: QueryDirective? = parse(Annotations::query, QueryDirective::create)

    val relationship: RelationshipDirective? = parse(Annotations::relationship, RelationshipDirective::create)

    val relationshipProperties: RelationshipPropertiesDirective? =
        parse(Annotations::relationshipProperties, RelationshipPropertiesDirective::create)

    val relayId: RelayIdDirective? = parse(Annotations::relayId, RelayIdDirective::create)

    val selectable: SelectableDirective? = parse(Annotations::selectable, SelectableDirective::create)

    val settable: SettableDirective? = parse(Annotations::settable, SettableDirective::create)

    val subscription: SubscriptionDirective? = parse(Annotations::subscription, SubscriptionDirective::create)

    val timestamp: TimestampDirective? = parse(Annotations::timestamp, TimestampDirective::create)

    val unique: UniqueDirective? = parse(Annotations::unique, UniqueDirective::create)

    private fun <T> parse(
        prop: KProperty1<Annotations, T?>,
        factory: (Directive) -> T
    ): T? {
        return unhandledDirectives.remove(prop.name)?.let { return factory(it) }
    }
}
