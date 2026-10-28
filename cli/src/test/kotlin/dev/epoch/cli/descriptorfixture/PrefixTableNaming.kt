package dev.epoch.cli.descriptorfixture

import org.hibernate.boot.model.naming.Identifier
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment

class PrefixTableNaming : PhysicalNamingStrategyStandardImpl() {
    override fun toPhysicalTableName(logicalName: Identifier?, jdbcEnvironment: JdbcEnvironment?): Identifier? =
        logicalName?.let { Identifier.toIdentifier("prefixed_" + it.text, it.isQuoted) }
}
