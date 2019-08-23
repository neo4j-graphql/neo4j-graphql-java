package org.neo4j.graphql

data class QueryContext @JvmOverloads constructor(
        /**
         * if true the <code>__typename</code> will be always returned for interfaces, no matter if it was queried or not
         */
        var queryTypeOfInterfaces: Boolean = false
)