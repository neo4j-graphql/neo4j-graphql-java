package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Node
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.dto.DeleteInput

fun createDeleteAndParams(
    parentVar: Node,
    deleteInput: DeleteInput,
    varName: String,
    chainStr: String?,
    node: org.neo4j.graphql.domain.Node,
    withVars: List<SymbolicName>,
    context: QueryContext?,
    schemaConfig: SchemaConfig,
    parameterPrefix: String,
    recursing: Boolean = false
) {

}
