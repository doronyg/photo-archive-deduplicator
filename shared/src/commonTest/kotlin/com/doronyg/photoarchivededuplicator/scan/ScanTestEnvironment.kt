package com.doronyg.photoarchivededuplicator.scan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Shared setup for scan tests: in-memory file system and database, with every
 * blocking call checked to run on the (virtual-time) IO dispatcher.
 */
class ScanTestEnvironment : AutoCloseable {
    private val testDispatcher = StandardTestDispatcher()
    val io = TrackingIoDispatcher(testDispatcher)
    val root = "/archive".toPath()
    val fakeFs = FakeFileSystem().apply { allowSymlinks = true }
    val fs = FlakyFileSystem(fakeFs).apply { onBlockingCall = io::assertOnIo }
    private val driver = createInMemoryDriver()
    val repository = DirectoryRepository(createFilesDatabase(IoGuardedDriver(driver, io)), io)

    fun test(body: suspend TestScope.() -> Unit) = runTest(testDispatcher, testBody = body)

    // testScheduler.currentTime is still experimental
    @OptIn(ExperimentalCoroutinesApi::class)
    fun TestScope.scanner(config: ScanConfig = ScanConfig()) = DirectoryScanner(
        fileSystem = fs,
        repository = repository,
        config = config,
        ioDispatcher = io,
        clock = { testScheduler.currentTime },
    )

    fun writeFile(path: Path, content: String = "x") {
        path.parent?.let { fakeFs.createDirectories(it) }
        fakeFs.write(path) { writeUtf8(content) }
    }

    override fun close() {
        driver.close()
        fakeFs.checkNoOpenFiles()
    }
}
