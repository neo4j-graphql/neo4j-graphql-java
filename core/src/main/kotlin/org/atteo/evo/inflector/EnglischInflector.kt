package org.atteo.evo.inflector

object EnglischInflector : English() {

    private val customRules = mutableListOf<Rule>()

    init {
        workaround_irregular("person", "people")
        // TODO
        workaround_irregular("two", "twos")
    }

    private fun workaround_irregular(singular: String, plural: String) {
        if (singular[0] == plural[0]) {
            customRules.add(
                RegExpRule(
                    "(?i)(" + singular[0] + ")" + singular.substring(1) + "$",
                    "$1" + plural.substring(1)
                )
            )
        } else {
            customRules.add(
                RegExpRule(
                    singular[0].uppercaseChar().toString() + "(?i)" + singular.substring(1) + "$",
                    plural[0].uppercaseChar()
                        .toString() + plural.substring(1)
                )
            )
            customRules.add(
                RegExpRule(
                    singular[0].lowercaseChar().toString() + "(?i)" + singular.substring(1) + "$",
                    plural[0].lowercaseChar().toString() + plural.substring(1)
                )
            )
        }
    }

    override fun getPlural(word: String?): String {
        for (rule in customRules) {
            val result = rule.getPlural(word)
            if (result != null) {
                return result
            }
        }
        return super.getPlural(word)
    }
}
