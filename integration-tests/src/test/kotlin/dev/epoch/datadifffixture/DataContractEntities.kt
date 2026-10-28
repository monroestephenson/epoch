package dev.epoch.datadifffixture

import jakarta.persistence.*
import org.hibernate.annotations.DiscriminatorOptions

enum class DataOldState { OPEN, CLOSED }
enum class DataExtendedState { OPEN, CLOSED, ARCHIVED }
enum class DataReorderedState { CLOSED, OPEN }

@Entity @Table(name = "data_name")
open class DataOldName {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.STRING) open var state: DataOldState = DataOldState.OPEN
}

@Entity @Table(name = "data_name")
open class DataNewName {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.STRING) open var state: DataExtendedState = DataExtendedState.ARCHIVED
}

@Entity @Table(name = "data_name")
open class DataStableName {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.STRING) open var state: DataReorderedState = DataReorderedState.CLOSED
}

@Entity @Table(name = "data_ordinal_add")
open class DataOldOrdinal {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.ORDINAL) open var state: DataOldState = DataOldState.OPEN
}

@Entity @Table(name = "data_ordinal_add")
open class DataNewOrdinal {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.ORDINAL) open var state: DataExtendedState = DataExtendedState.ARCHIVED
}

@Entity @Table(name = "data_ordinal_reorder")
open class DataOldOrder {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.ORDINAL) open var state: DataOldState = DataOldState.OPEN
}

@Entity @Table(name = "data_ordinal_reorder")
open class DataNewOrder {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.ORDINAL) open var state: DataReorderedState = DataReorderedState.CLOSED
}

@Entity @Table(name = "data_mode")
open class DataOldMode {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.STRING) open var state: DataOldState = DataOldState.OPEN
}

@Entity @Table(name = "data_mode")
open class DataNewMode {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.ORDINAL) open var state: DataOldState = DataOldState.OPEN
}

@Entity @Table(name = "data_nullable")
open class DataOldNonNull {
    @field:Id open var id: Long = 1
    open var label: String = "old"
}

@Entity @Table(name = "data_nullable")
open class DataNewNullable {
    @field:Id open var id: Long = 1
    open var label: String? = null
}

@Entity @Table(name = "data_creatures")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "dtype")
abstract class DataOldCreature {
    @field:Id open var id: Long = 1
    open var label: String? = null
}

@Entity @DiscriminatorValue("DOG")
open class DataOldDog : DataOldCreature()

@Entity @Table(name = "data_creatures")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "dtype")
abstract class DataNewCreature {
    @field:Id open var id: Long = 1
    open var label: String? = null
}

@Entity @DiscriminatorValue("DOG")
open class DataNewDog : DataNewCreature()

@Entity @DiscriminatorValue("CAT")
open class DataNewCat : DataNewCreature()

@Entity @Table(name = "data_creatures")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "dtype")
@DiscriminatorOptions(force = true)
abstract class DataFilteredCreature {
    @field:Id open var id: Long = 1
    open var label: String? = null
}

@Entity @DiscriminatorValue("DOG")
open class DataFilteredDog : DataFilteredCreature()

@Converter
class DataOldConverter : AttributeConverter<String, String> {
    override fun convertToDatabaseColumn(attribute: String?): String? = attribute?.let { "v1:$it" }
    override fun convertToEntityAttribute(dbData: String?): String? = dbData?.removePrefix("v1:")
}

/** Deliberately a different artifact with equivalent sample behavior, not a proof of equivalence. */
@Converter
class DataNewConverter : AttributeConverter<String, String> {
    override fun convertToDatabaseColumn(attribute: String?): String? = attribute?.let { "v1:" + it }
    override fun convertToEntityAttribute(dbData: String?): String? = dbData?.removePrefix("v1:")
}

@Entity @Table(name = "data_converter")
open class DataOldConverted {
    @field:Id open var id: Long = 1
    @field:Convert(converter = DataOldConverter::class) open var label: String = "sample"
}

@Entity @Table(name = "data_converter")
open class DataNewConverted {
    @field:Id open var id: Long = 1
    @field:Convert(converter = DataNewConverter::class) open var label: String = "sample"
}

@Entity @Table(name = "data_type_change")
open class DataOldString {
    @field:Id open var id: Long = 1
    open var label: String = "old"
}

@Entity @Table(name = "data_type_change")
open class DataNewChar {
    @field:Id open var id: Long = 1
    open var label: Char = 'X'
}
