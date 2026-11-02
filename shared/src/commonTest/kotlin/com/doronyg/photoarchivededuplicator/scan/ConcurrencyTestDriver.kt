package com.doronyg.photoarchivededuplicator.scan

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.doronyg.photoarchivededuplicator.IoLane
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.time.Duration.Companion.seconds

/** Suspends only after the selected real SQLite mutation has executed. */
internal class ConcurrencyTestDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val statements = mutableListOf<String>()
    var beforeStatement: (String) -> Unit = {}
    var pauseAfter: ((String) -> Boolean)? = null

    override fun <R> executeQuery(
        identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        statements += sql
        beforeStatement(sql)
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(
        identifier: Int?, sql: String, parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        statements += sql
        beforeStatement(sql)
        val result = delegate.execute(identifier, sql, parameters, binders)
        if (pauseAfter?.invoke(sql) != true) return result
        pauseAfter = null
        return QueryResult.AsyncValue {
            val rows = result.await()
            val transaction = checkNotNull(delegate.currentTransaction())
            entered.complete(Unit)
            release.await()
            check(delegate.currentTransaction() === transaction)
            rows
        }
    }
}

internal class ConcurrencyFixture(scope: TestScope) {
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private val io = TrackingIoDispatcher(dispatcher)
    val lane = IoLane("concurrency-database", io)
    val driver = ConcurrencyTestDriver(IoGuardedDriver(createInMemoryDriver(), io))
    val repository = DirectoryRepository(createFilesDatabase(driver), databaseLane = lane)
    val root = "/archive".toPath()
    val fakeFs = FakeFileSystem()
    val fs = FlakyFileSystem(fakeFs).apply { onBlockingCall = io::assertOnIo }

    fun scanner(config: ScanConfig = ScanConfig()) =
        DirectoryScanner(fs, repository, config, ioDispatcher = io, clock = { 900 })

    fun write(path: Path, text: String = "photo") {
        fakeFs.createDirectories(path.parent!!)
        fakeFs.write(path) { writeUtf8(text) }
    }

    suspend fun close() {
        driver.release.complete(Unit)
        lane.close { driver.close() }
        fakeFs.checkNoOpenFiles()
    }
}

internal fun concurrencyTest(body: suspend TestScope.(ConcurrencyFixture) -> Unit) =
    runTest(timeout = 10.seconds) {
        val fixture = ConcurrencyFixture(this)
        try {
            body(fixture)
        } finally {
            currentCoroutineContext().cancelChildren()
            fixture.close()
        }
    }

internal fun isRecordSuccess(sql: String): Boolean =
    sql.startsWith("UPDATE scanDirectory") && "lastScanned =" in sql
