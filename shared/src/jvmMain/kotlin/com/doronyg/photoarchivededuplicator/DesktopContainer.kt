package com.doronyg.photoarchivededuplicator

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import com.doronyg.photoarchivededuplicator.scan.DirectoryScanner
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.util.Properties

private const val APP_DIRECTORY_NAME = "PhotoArchiveDeduplicator"
private const val DATABASE_FILE_NAME = "inventory.db"
private const val BUSY_TIMEOUT_MILLIS = 5_000

/** Per-user app data folder following each OS's convention. */
fun desktopDataDirectory(): Path {
    val home = System.getProperty("user.home").toPath()
    val os = System.getProperty("os.name").lowercase()
    val base = when {
        "mac" in os -> home / "Library" / "Application Support"
        "win" in os -> System.getenv("APPDATA")?.toPath() ?: home
        else -> System.getenv("XDG_DATA_HOME")?.toPath() ?: (home / ".local" / "share")
    }
    return base / APP_DIRECTORY_NAME
}

/** Builds the container with the inventory in a SQLite file. Call once, at startup. */
fun createDesktopContainer(dataDirectory: Path = desktopDataDirectory()): AppContainer {
    FileSystem.SYSTEM.createDirectories(dataDirectory)
    val properties = Properties().apply {
        // WAL lets the browser read while the scanner writes
        put("journal_mode", "WAL")
        put("busy_timeout", BUSY_TIMEOUT_MILLIS.toString())
    }
    val driver = JdbcSqliteDriver(
        url = "jdbc:sqlite:${dataDirectory / DATABASE_FILE_NAME}",
        properties = properties,
        schema = FilesDatabase.Schema.synchronous(),
    )
    return AppContainer(driver) { repository -> DirectoryScanner(FileSystem.SYSTEM, repository) }
}
