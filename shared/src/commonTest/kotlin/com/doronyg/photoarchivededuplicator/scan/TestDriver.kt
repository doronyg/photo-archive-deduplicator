package com.doronyg.photoarchivededuplicator.scan

import app.cash.sqldelight.db.SqlDriver

/** In-memory SQLite driver with the schema already created. */
expect fun createInMemoryDriver(): SqlDriver

/** Two connections to a named in-memory database, to exercise the real JDBC upgrade path. */
expect fun createMigrationTestDriver(migrate: Boolean): SqlDriver
