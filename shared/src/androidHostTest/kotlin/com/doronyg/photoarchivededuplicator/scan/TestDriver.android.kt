package com.doronyg.photoarchivededuplicator.scan

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.doronyg.photoarchivededuplicator.db.FilesDatabase

actual fun createInMemoryDriver(): SqlDriver =
    JdbcSqliteDriver("jdbc:sqlite::memory:", schema = FilesDatabase.Schema.synchronous())

actual fun createMigrationTestDriver(migrate: Boolean): SqlDriver {
    val url = "jdbc:sqlite:file:inventory-v1-migration?mode=memory&cache=shared"
    return if (migrate) JdbcSqliteDriver(url, schema = FilesDatabase.Schema.synchronous())
    else JdbcSqliteDriver(url)
}
