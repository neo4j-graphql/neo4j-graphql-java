package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.FunctionInvocation

object ApocFunctions {

    object DateFunctions {
        private enum class Date(private val implName: String) : FunctionInvocation.FunctionDefinition {

            CONVERT_FORMAT("apoc.date.convertFormat");

            override fun getImplementationName() = implName
        }

        fun convertFormat(temporal: Expression, currentFormat: Expression, convertTo: Expression): FunctionInvocation =
            FunctionInvocation.create(Date.CONVERT_FORMAT, temporal, currentFormat, convertTo)
    }

    val date = DateFunctions
}
