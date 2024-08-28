package org.neo4j.graphql.domain.fields

import graphql.language.Comment
import graphql.language.Description
import graphql.language.InputValueDefinition
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.directives.PopulatedByDirective
import org.neo4j.graphql.domain.directives.TimestampDirective
import org.neo4j.graphql.isRequired

/**
 * Representation a ObjectTypeDefinitionNode field.
 */
sealed class BaseField(
    var fieldName: String,
    var typeMeta: TypeMeta,
    var annotations: FieldAnnotations
) {
    val deprecatedDirective
        get() = annotations.otherDirectives.firstOrNull { it.name == "deprecated" }
    var arguments: List<InputValueDefinition> = emptyList()
    val auth get() = annotations.auth

    var description: Description? = null
    var comments: List<Comment> = emptyList()

    //    var ignored: Boolean = false
    open val dbPropertyName get() = annotations.alias?.property ?: fieldName
    open lateinit var owner: FieldContainer<*>

    override fun toString(): String {
        return "Field: ${getOwnerName()}::$fieldName"
    }

    open val generated: Boolean get() = false

    val required: Boolean get() = typeMeta.type.isRequired()

    fun getOwnerName() = owner.let {
        when (it) {
            is Node -> it.name
            is Interface -> it.name
            is RelationshipProperties -> it.typeName
        }
    }

    val interfaceField2: BaseField?
        get() = (owner as? ImplementingType)
            ?.interfaces
            ?.mapNotNull { it.getField(fieldName) }
            ?.firstOrNull()
            ?.let { it.interfaceField2 ?: it }

    val interfaceField
        get() = (owner as? Node)
            ?.interfaces
            ?.mapNotNull { it.getField(fieldName) }
            ?.firstOrNull()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BaseField

        if (fieldName != other.fieldName) return false

        return true
    }

    override fun hashCode(): Int {
        return fieldName.hashCode()
    }

    fun isCypher() = this.annotations.cypher != null
    fun isCustomResolvable() = this.annotations.customResolver != null

    open fun isNonGeneratedField(): Boolean = false

    // TODO rename
    fun timestampCreateIsGenerated() =
        this.annotations.timestamp?.operations?.contains(TimestampDirective.TimeStampOperation.CREATE) == true

    fun timestampUpdateIsGenerated() =
        this.annotations.timestamp?.operations?.contains(TimestampDirective.TimeStampOperation.UPDATE) == true


    fun isCreateInputField() =
        isNonGeneratedField()
                && annotations.settable?.onCreate != false
                && !timestampCreateIsGenerated()
                && !this.populatedByCreateIsGenerated()

    fun isUpdateInputField() =
        isNonGeneratedField()
                && annotations.settable?.onUpdate != false
                && !timestampUpdateIsGenerated()
                && !this.populatedByUpdateIsGenerated()

    private fun populatedByCreateIsGenerated() = annotations.populatedBy
        ?.operations
        ?.contains(PopulatedByDirective.PopulatedByOperation.CREATE) == true

    private fun populatedByUpdateIsGenerated() = annotations.populatedBy
        ?.operations
        ?.contains(PopulatedByDirective.PopulatedByOperation.UPDATE) == true

    open fun isAggregationFilterable() = !isCustomResolvable() && !isCypher()
            && annotations.filterable?.byAggregate != false

    open fun isEventPayloadField() = false
    fun isFilterableByValue() = annotations.filterable?.byValue != false
    fun isFilterableByAggregate() = annotations.filterable?.byAggregate != false
}
