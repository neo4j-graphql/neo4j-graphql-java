package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.VoidCall

object ApocFunctions {

    object DateFunctions {
        private enum class Date(private val implName: String) : FunctionInvocation.FunctionDefinition {

            CONVERT_FORMAT("apoc.date.convertFormat");

            override fun getImplementationName() = implName
        }

        fun convertFormat(temporal: Expression, currentFormat: Expression, convertTo: Expression): FunctionInvocation =
            FunctionInvocation.create(Date.CONVERT_FORMAT, temporal, currentFormat, convertTo)
    }

    object UtilFunctions {
        private enum class Util(private val implName: String) : FunctionInvocation.FunctionDefinition {

            VALIDATE("apoc.util.validate"),
            VALIDATE_PREDICATE("apoc.util.validatePredicate");

            override fun getImplementationName() = implName
        }

        fun ExposesCall<StatementBuilder.OngoingInQueryCallWithoutArguments>.callApocValidate(
            predicate: Expression,
            message: Expression,
            params: ListExpression
        ): VoidCall =
            this.call(Util.VALIDATE.implementationName)
                .withArgs(predicate, message, params)
                .withoutResults()

        fun validate(predicate: Expression, message: Expression, params: ListExpression): FunctionInvocation =
            FunctionInvocation.create(Util.VALIDATE, predicate, message, params)

        fun validatePredicate(predicate: Expression, message: Expression, params: ListExpression): FunctionInvocation =
            FunctionInvocation.create(Util.VALIDATE_PREDICATE, predicate, message, params)
    }

    val date = DateFunctions

    val util = UtilFunctions

}
