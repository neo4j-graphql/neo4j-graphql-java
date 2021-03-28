package org.neo4j.graphql

data class SchemaConfig @JvmOverloads constructor(
        val query: CRUDConfig = CRUDConfig(),
        val mutation: CRUDConfig = CRUDConfig(),
        /**
         * if true, the top level fields of the Query-type will be capitalized
         */
        val capitalizeQueryFields: Boolean = false,
        /**
         * Defines the way the input for queries and mutations are generated
         */
        val queryOptionStyle: InputStyle = InputStyle.ARGUMENT_PER_FIELD,
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
