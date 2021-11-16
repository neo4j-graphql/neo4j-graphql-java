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
}
