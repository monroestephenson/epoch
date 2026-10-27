package dev.epoch.literalfixture

import jakarta.persistence.EntityManager
import java.sql.Connection

/** Actual Kotlin bytecode fixtures; discovery never executes the DAO. */
class LiteralDao {
    fun indentation(manager: EntityManager) {
        manager.createQuery("""
            select c
            from RepositoryCustomer c
        """.trimIndent())
    }

    fun defaultMargin(manager: EntityManager) {
        manager.createQuery("""
            |select c
            |from RepositoryCustomer c
        """.trimMargin())
    }

    fun customMargin(manager: EntityManager) {
        manager.createQuery("""
            #select c
            #from RepositoryCustomer c
        """.trimMargin("#"))
    }

    fun dynamicTemplate(manager: EntityManager, column: String) {
        manager.createNativeQuery("select $column from repository_customer")
    }

    fun finiteNative(manager: EntityManager, first: Boolean) {
        val sql = if (first) "select id from repository_customer" else "select email from repository_customer"
        manager.createNativeQuery(sql)
    }

    fun finitePrepared(connection: Connection, first: Boolean) {
        val sql = if (first) "delete from repository_customer where id = ?" else "delete from repository_customer where email = ?"
        connection.prepareStatement(sql).executeUpdate()
    }

    fun bounded(manager: EntityManager, choice: Int) {
        val sql = when (choice) {
            0 -> "select 0"; 1 -> "select 1"; 2 -> "select 2"; 3 -> "select 3"
            4 -> "select 4"; 5 -> "select 5"; 6 -> "select 6"; 7 -> "select 7"
            8 -> "select 8"; 9 -> "select 9"; 10 -> "select 10"; 11 -> "select 11"
            12 -> "select 12"; 13 -> "select 13"; 14 -> "select 14"; else -> "select 15"
        }
        manager.createNativeQuery(sql)
    }

    fun excessive(manager: EntityManager, choice: Int) {
        val sql = when (choice) {
            0 -> "select 0"; 1 -> "select 1"; 2 -> "select 2"; 3 -> "select 3"
            4 -> "select 4"; 5 -> "select 5"; 6 -> "select 6"; 7 -> "select 7"
            8 -> "select 8"; 9 -> "select 9"; 10 -> "select 10"; 11 -> "select 11"
            12 -> "select 12"; 13 -> "select 13"; 14 -> "select 14"; 15 -> "select 15"; else -> "select 16"
        }
        manager.createNativeQuery(sql)
    }
}
