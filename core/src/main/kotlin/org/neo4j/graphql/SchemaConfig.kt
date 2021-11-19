package org.neo4j.graphql

data class SchemaConfig @JvmOverloads constructor(
        val query: CRUDConfig = CRUDConfig(),
        val mutation: CRUDConfig = CRUDConfig(),

        /**
         * If true, the top level fields of the Query-type will be capitalized
         */
        val capitalizeQueryFields: Boolean = false,

        /**
         * If true, the generated fields for query or mutation will use the plural of the types name.
         * When [inputStyle] is set to [InputStyle.INPUT_TYPE], this setting will be ignored / set to `true`.
         */
        val pluralizeFields: Boolean = false,

        /**
         * Defines the way the input for query options (for paging and sorting) is handled
         */
        val queryOptionStyle: InputStyle = InputStyle.ARGUMENT_PER_FIELD,

        /**
         * Defines the way the input arguments are handled, if the input style is set to [InputStyle.INPUT_TYPE]
         * batch operations are used for mutations
         */
        val inputStyle: InputStyle = InputStyle.ARGUMENT_PER_FIELD,

        /**
         * If enabled the `filter` argument will be named `where` and the input type will be named `<typeName>Where`.
         * additionally, the separated filter arguments will no longer be generated.
         */
        val useWhereFilter: Boolean = false,

        /**
         * If enabled the `Date`, `Time`, `LocalTime`, `DateTime` and `LocalDateTime` are used as scalars
         */
        val useTemporalScalars: Boolean = false,

        /**
         * If enabled, the results of create- and update-mutations will get wrapped, so that there are two nested
         * fields, one with the cypher result itself and a second containing query statistics. If this setting is
         * enabled a [DataFetchingInterceptorWithStatistics] needs to be registered when augmenting the schema with the
         * [SchemaBuilder.buildSchema].
         *
         * If this setting is enabled, [wrapMutationResults] is implicitly set to `true`.
         */
        val enableStatistics: Boolean = false,

        /**
         * If enabled, the results of create- and update-mutations will get wrapped, so that there is a nested field,
         * with the cypher result itself. This is implicitly enabled if [enableStatistics] is set to `true`.
         */
        val wrapMutationResults: Boolean = false,
) {
    data class CRUDConfig(val enabled: Boolean = true, val exclude: List<String> = emptyList())

    enum class InputStyle {
        /**
         * Separate arguments are generated for the query and / or mutation fields
         */
        @Deprecated(message = "Will be removed in the next major release", replaceWith = ReplaceWith(expression = "INPUT_TYPE"))
        ARGUMENT_PER_FIELD,

        /**
         * All fields are encapsulated into an input type used as one argument in query and / or mutation fields
         */
        INPUT_TYPE,
    }

    val shouldWrapMutationResults get() = wrapMutationResults || enableStatistics
}
