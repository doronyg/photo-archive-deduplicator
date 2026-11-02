package com.doronyg.photoarchivededuplicator.scan

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * Stands in for Dispatchers.IO in tests. Delegates scheduling (so virtual time still works)
 * and records whether the current code is running inside one of its tasks.
 */
class TrackingIoDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var dispatchCount = 0
        private set
    var isRunningTask = false
        private set

    override fun isDispatchNeeded(context: CoroutineContext) = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchCount++
        delegate.dispatch(context, Runnable {
            isRunningTask = true
            try {
                block.run()
            } finally {
                isRunningTask = false
            }
        })
    }

    fun assertOnIo(operation: String) {
        check(isRunningTask) { "Blocking call '$operation' made outside the IO dispatcher" }
    }
}

/** Fails any statement or transaction that is not issued from the IO dispatcher. */
class IoGuardedDriver(
    private val delegate: SqlDriver,
    private val io: TrackingIoDispatcher,
    private val beforeStatement: (String) -> Unit = {},
) : SqlDriver by delegate {
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        io.assertOnIo(sql)
        beforeStatement(sql)
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        io.assertOnIo(sql)
        beforeStatement(sql)
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        io.assertOnIo("BEGIN TRANSACTION")
        return delegate.newTransaction()
    }
}
