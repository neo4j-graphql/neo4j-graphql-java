package org.neo4j.opencypherdsl

// TODO remove after https://github.com/neo4j-contrib/cypher-dsl/issues/63 is merged
fun point(parameter: Parameter) = FunctionInvocation("point", parameter)