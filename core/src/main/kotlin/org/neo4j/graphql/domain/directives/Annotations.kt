package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import graphql.schema.idl.TypeDefinitionRegistry
import kotlin.reflect.KProperty1

sealed interface CommonAnnotations {
    val otherDirectives: List<Directive>
}

interface EntityAnnotations : CommonAnnotations {
    val plural: PluralDirective?
    val query: QueryDirective?
}

interface ImplementingTypeAnnotations : EntityAnnotations {
    val limit: LimitDirective?
}

interface NodeAnnotations : ImplementingTypeAnnotations {
    val node: NodeDirective?
}

interface InterfaceAnnotations : ImplementingTypeAnnotations {
    val relationshipProperties: RelationshipPropertiesDirective?
}

interface UnionAnnotations : EntityAnnotations

interface FieldAnnotations : CommonAnnotations {
    val alias: AliasDirective?
    val coalesce: CoalesceDirective?
    val customResolver: CustomResolverDirective?
    val default: DefaultDirective?
    val filterable: FilterableDirective?
    val id: IdDirective?
    val private: PrivateDirective?
    val relationship: RelationshipDirective?
    val relationshipBaseDirective: RelationshipBaseDirective?
    val relayId: RelayIdDirective?
    val selectable: SelectableDirective?
}

interface SchemaAnnotations : CommonAnnotations {
    val query: QueryDirective?
}

class Annotations(
    directives: Map<String, Directive>,
    typeDefinitionRegistry: TypeDefinitionRegistry,
    ownerName: String? = null,
) : ImplementingTypeAnnotations, NodeAnnotations, InterfaceAnnotations, UnionAnnotations, FieldAnnotations,
    SchemaAnnotations {

    constructor(
        directives: Collection<Directive>,
        typeDefinitionRegistry: TypeDefinitionRegistry,
        parentTypeName: String? = null,
    ) : this(
        directives.associateBy { it.name },
        typeDefinitionRegistry,
        parentTypeName
    )

    private val libraryDirectives = mutableSetOf<String>()

    private val unhandledDirectives: MutableMap<String, Directive> = directives.toMutableMap()

    override val otherDirectives get() = this.unhandledDirectives.values.toList()

    override val alias: AliasDirective? = parse(Annotations::alias, AliasDirective::create)

    override val coalesce: CoalesceDirective? = parse(Annotations::coalesce, CoalesceDirective::create)

    override val customResolver: CustomResolverDirective? =
        parse(Annotations::customResolver) { CustomResolverDirective.create(it, ownerName, typeDefinitionRegistry) }

    override val default: DefaultDirective? = parse(Annotations::default, DefaultDirective::create)

    override val filterable: FilterableDirective? = parse(Annotations::filterable, FilterableDirective::create)

    override val id: IdDirective? = parse(Annotations::id) { IdDirective.INSTANCE }

    override val limit: LimitDirective? = parse(Annotations::limit, LimitDirective::create)

    override val node: NodeDirective? = parse(Annotations::node, NodeDirective::create)

    override val plural: PluralDirective? = parse(Annotations::plural, PluralDirective::create)

    override val private: PrivateDirective? = parse(Annotations::private) { PrivateDirective.INSTANCE }

    override val query: QueryDirective? = parse(Annotations::query, QueryDirective::create)

    override val relationship: RelationshipDirective? = parse(Annotations::relationship, RelationshipDirective::create)

    override val relationshipBaseDirective: RelationshipBaseDirective? get() = relationship

    override val relationshipProperties: RelationshipPropertiesDirective? =
        parse(Annotations::relationshipProperties) { RelationshipPropertiesDirective.INSTANCE }

    override val relayId: RelayIdDirective? = parse(Annotations::relayId) { RelayIdDirective.INSTANCE }

    override val selectable: SelectableDirective? = parse(Annotations::selectable, SelectableDirective::create)

    private fun <T> parse(
        prop: KProperty1<Annotations, T?>,
        factory: (Directive) -> T,
    ): T? {
        libraryDirectives.add(prop.name)
        return unhandledDirectives.remove(prop.name)?.let { return factory(it) }
    }

    companion object {
        val LIBRARY_DIRECTIVES = Annotations(emptyMap(), TypeDefinitionRegistry()).libraryDirectives
    }
}
