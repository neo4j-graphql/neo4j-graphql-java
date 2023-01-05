package org.neo4j.graphql

data class SchemaConfig @JvmOverloads constructor(
    val query: CRUDConfig = CRUDConfig(),
    val mutation: CRUDConfig = CRUDConfig(),

    /**
     * if true, the top level fields of the Query-type will be capitalized
     */
    val capitalizeQueryFields: Boolean = false,

    /**
     * if true, the generated fields for query or mutation will use the plural of the types name
     */
    val pluralizeFields: Boolean = false,

    /**
     * Defines the way the input for queries and mutations are generated
     */
    val queryOptionStyle: InputStyle = InputStyle.ARGUMENT_PER_FIELD,

    /**
     * if enabled the `filter` argument will be named `where` and the input type will be named `<typeName>Where`.
     * additionally, the separated filter arguments will no longer be generated.
     */
    val useWhereFilter: Boolean = false,

    /**
     * if enabled the `Date`, `Time`, `LocalTime`, `DateTime` and `LocalDateTime` are used as scalars
     */
    val useTemporalScalars: Boolean = false,

    val capitaliseFilterOperations: Boolean = true,

    /**
     * To enable the inclusion of this the matches filter.
     *
     * The nature of RegEx matching means that on an unprotected API, this could potentially be used to execute a ReDoS
     * attack (https://owasp.org/www-community/attacks/Regular_expression_Denial_of_Service_-_ReDoS) against the backing
     * Neo4j database.
     */
    val enableRegex: Boolean = false,

    val namingStrategy: NamingStrategy = NamingStrategy(),

    val features: Neo4jFeaturesSettings = Neo4jFeaturesSettings()
) {
    data class CRUDConfig(val enabled: Boolean = true, val exclude: List<String> = emptyList())

    enum class InputStyle {
        /**
         * Separate arguments are generated for the query and / or mutation fields
         */
        @Deprecated(
            message = "Will be removed in the next major release",
            replaceWith = ReplaceWith(expression = "INPUT_TYPE")
        )
        ARGUMENT_PER_FIELD,

        /**
         * All fields are encapsulated into an input type used as one argument in query and / or mutation fields
         */
        INPUT_TYPE,
    }

    data class Neo4jFeaturesSettings(
        val filters: Neo4jFiltersSettings = Neo4jFiltersSettings()
    )

    data class Neo4jFiltersSettings(
        // TODO should we also use feature toggles for strings? https://github.com/neo4j/graphql/issues/2657#issuecomment-1369858159
        val string: Neo4jStringFiltersSettings = Neo4jStringFiltersSettings()
    )

    data class Neo4jStringFiltersSettings(
        val gt: Boolean = false,
        val gte: Boolean = false,
        val lt: Boolean = false,
        val lte: Boolean = false,
    )
}
