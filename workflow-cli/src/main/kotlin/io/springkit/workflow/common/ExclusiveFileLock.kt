package io.springkit.workflow.common

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import okio.Path

/** A small Java NIO wrapper for the inter-process lock that Okio does not expose. */
class ExclusiveFileLock
private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
  override fun close() {
    runCatching { lock.release() }
    runCatching { channel.close() }
  }

  companion object {
    fun acquire(path: Path): ExclusiveFileLock =
        tryAcquire(path) ?: throw IllegalStateException("file is already locked: $path")

    /** Returns null when another process currently owns the lock. */
    fun tryAcquire(path: Path): ExclusiveFileLock? {
      path.parent?.toNioPath()?.let { java.nio.file.Files.createDirectories(it) }
      val channel = FileChannel.open(path.toNioPath(), CREATE, WRITE)
      return try {
        val lock =
            try {
              channel.tryLock()
            } catch (_: OverlappingFileLockException) {
              null
            }
        if (lock == null) {
          channel.close()
          null
        } else {
          ExclusiveFileLock(channel, lock)
        }
      } catch (failure: Throwable) {
        channel.close()
        throw failure
      }
    }
  }
}
