package com.doronyg.photoarchivededuplicator.scan

import app.cash.sqldelight.db.SqlDriver

/** In-memory SQLite driver with the schema already created. */
expect fun createInMemoryDriver(): SqlDriver
