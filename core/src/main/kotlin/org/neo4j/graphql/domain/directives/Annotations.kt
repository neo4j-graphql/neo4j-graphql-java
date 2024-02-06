package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.domain.Node
import kotlin.reflect.KProperty1

sealed interface CommonAnnotations {
    val otherDirectives: List<Directive>
}

interface EntityAnnotations : CommonAnnotations {
    val plural: PluralDirective?
    val query: QueryDirective?
}

interface ImplementingTypeAnnotations : EntityAnnotations {
    @Deprecated("Use @authentication and / or @authorization instead")
    val auth: AuthDirective?

    val limit: LimitDirective?
    val mutation: MutationDirective?
}

interface NodeAnnotations : ImplementingTypeAnnotations {
    val authentication: AuthenticationDirective?
    val authorization: AuthorizationDirective?
    val fulltext: FulltextDirective?
    val node: NodeDirective?
    val subscription: SubscriptionDirective?
}

interface InterfaceAnnotations : ImplementingTypeAnnotations {
    val relationshipProperties: RelationshipPropertiesDirective?
}

interface UnionAnnotations : EntityAnnotations
interface FieldAnnotations : CommonAnnotations {
    @Deprecated("Use @authentication and / or @authorization instead")
    val auth: AuthDirective?

    val alias: AliasDirective?
    val authentication: AuthenticationDirective?
    val authorization: AuthorizationDirective?
    val coalesce: CoalesceDirective?
    val customResolver: CustomResolverDirective?
    val cypher: CypherDirective?
    val default: DefaultDirective?
    val filterable: FilterableDirective?
    val id: IdDirective?
    val jwtClaim: JwtClaimDirective?
    val populatedBy: PopulatedByDirective?
    val private: PrivateDirective?
    val relationship: RelationshipDirective?
    val relayId: RelayIdDirective?
    val selectable: SelectableDirective?
    val settable: SettableDirective?
    val timestamp: TimestampDirective?
    val unique: UniqueDirective?
}

interface SchemaAnnotations : CommonAnnotations {
    val authentication: AuthenticationDirective?
    val query: QueryDirective?
    val subscription: SubscriptionDirective?
}

class Annotations(
    directives: Map<String, Directive>,
    jwtShape: Node?,
) : ImplementingTypeAnnotations, NodeAnnotations, InterfaceAnnotations, UnionAnnotations, FieldAnnotations,
    SchemaAnnotations {

    constructor(directives: Collection<Directive>, jwtShape: Node?) : this(directives.associateBy { it.name }, jwtShape)

    private val unhandledDirectives: MutableMap<String, Directive> = directives.toMutableMap()

    override val otherDirectives get() = this.unhandledDirectives.values.toList()

    override val auth: AuthDirective? = parse(Annotations::auth, AuthDirective::create)

    override val alias: AliasDirective? = parse(Annotations::alias, AliasDirective::create)

    override val authentication: AuthenticationDirective? =
        parse(Annotations::authentication) { AuthenticationDirective.create(it, jwtShape) }

    override val authorization: AuthorizationDirective? =
        parse(Annotations::authorization) { AuthorizationDirective.create(it, jwtShape) }

    override val coalesce: CoalesceDirective? = parse(Annotations::coalesce, CoalesceDirective::create)

    override val customResolver: CustomResolverDirective? =
        parse(Annotations::customResolver, CustomResolverDirective::create)

    override val cypher: CypherDirective? = parse(Annotations::cypher, CypherDirective::create)

    override val default: DefaultDirective? = parse(Annotations::default, DefaultDirective::create)

    override val filterable: FilterableDirective? = parse(Annotations::filterable, FilterableDirective::create)

    override val fulltext: FulltextDirective? = parse(Annotations::fulltext, FulltextDirective::create)

    override val id: IdDirective? = parse(Annotations::id, IdDirective::create)

    override val jwtClaim: JwtClaimDirective? = parse(Annotations::jwtClaim, JwtClaimDirective::create)

    val jwt: JwtDirective? = parse(Annotations::jwt, JwtDirective::create)

    override val limit: LimitDirective? = parse(Annotations::limit, LimitDirective::create)

    override val mutation: MutationDirective? = parse(Annotations::mutation, MutationDirective::create)

    override val node: NodeDirective? = parse(Annotations::node, NodeDirective::create)

    override val plural: PluralDirective? = parse(Annotations::plural, PluralDirective::create)

    override val populatedBy: PopulatedByDirective? = parse(Annotations::populatedBy, PopulatedByDirective::create)

    override val private: PrivateDirective? = parse(Annotations::private, PrivateDirective::create)

    override val query: QueryDirective? = parse(Annotations::query, QueryDirective::create)

    override val relationship: RelationshipDirective? = parse(Annotations::relationship, RelationshipDirective::create)

    override val relationshipProperties: RelationshipPropertiesDirective? =
        parse(Annotations::relationshipProperties, RelationshipPropertiesDirective::create)

    override val relayId: RelayIdDirective? = parse(Annotations::relayId, RelayIdDirective::create)

    override val selectable: SelectableDirective? = parse(Annotations::selectable, SelectableDirective::create)

    override val settable: SettableDirective? = parse(Annotations::settable, SettableDirective::create)

    override val subscription: SubscriptionDirective? = parse(Annotations::subscription, SubscriptionDirective::create)

    override val timestamp: TimestampDirective? = parse(Annotations::timestamp, TimestampDirective::create)

    override val unique: UniqueDirective? = parse(Annotations::unique, UniqueDirective::create)

    private fun <T> parse(
        prop: KProperty1<Annotations, T?>,
        factory: (Directive) -> T
    ): T? {
        return unhandledDirectives.remove(prop.name)?.let { return factory(it) }
    }
}
