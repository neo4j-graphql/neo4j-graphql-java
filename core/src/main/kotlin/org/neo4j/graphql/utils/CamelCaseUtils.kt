package org.neo4j.graphql.utils

import org.neo4j.graphql.toLowerCase
import org.neo4j.graphql.toUpperCase

object CamelCaseUtils {

    private val UPPERCASE = Regex("[\\p{Lu}]")
    private val LOWERCASE = Regex("[\\p{Ll}]")
    private val IDENTIFIER = Regex("([\\p{Alpha}\\p{N}_]|\$)")
    private val SEPARATORS = Regex("[_.\\- ]+")

    private val LEADING_SEPARATORS = Regex("^$SEPARATORS")
    private val SEPARATORS_AND_IDENTIFIER = Regex("$SEPARATORS$IDENTIFIER")
    private val NUMBERS_AND_IDENTIFIER = Regex("\\d+$IDENTIFIER")

    private fun preserveCamelCase(
        string: String,
        toLowerCase: (String) -> String,
        toUpperCase: (String) -> String
    ): String {
        var isLastCharLower = false
        var isLastCharUpper = false
        var isLastLastCharUpper = false
        val result = StringBuilder()
        for (character in string) {
            val strChar = character.toString()
            if (isLastCharLower && UPPERCASE.matches(strChar)) {
                result.append('-')
                isLastCharLower = false
                isLastLastCharUpper = isLastCharUpper
                isLastCharUpper = true
            } else if (isLastCharUpper && isLastLastCharUpper && LOWERCASE.matches(strChar)) {
                result.insert(result.length - 1, '-')
                isLastCharUpper = false
                isLastCharLower = true
            } else {
                isLastCharLower = toLowerCase(strChar) == strChar && toUpperCase(strChar) != strChar
                isLastLastCharUpper = isLastCharUpper
                isLastCharUpper = toUpperCase(strChar) == strChar && toLowerCase(strChar) != strChar
            }
            result.append(character)
        }

        return result.toString()
    }


    private fun postProcess(input: String, toUpperCase: (String) -> String): String {

        return input.replace(SEPARATORS_AND_IDENTIFIER) { toUpperCase(it.groupValues[1]) }
            .replace(NUMBERS_AND_IDENTIFIER) { toUpperCase(it.value) }
    }

    fun camelCase(input1: String): String {

        var input = input1.trim()

        if (input.isEmpty()) {
            return ""
        }

        val toLowerCase = String::toLowerCase
        val toUpperCase = String::toUpperCase

        if (input.length == 1) {
            return toLowerCase(input)
        }

        val hasUpperCase = input != toLowerCase(input)

        if (hasUpperCase) {
            input = preserveCamelCase(input, toLowerCase, toUpperCase)
        }

        input = input.replace(LEADING_SEPARATORS, "")
        input = toLowerCase(input)

        return postProcess(input, toUpperCase)
    }
}
