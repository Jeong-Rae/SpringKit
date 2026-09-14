package io.springkit.workflow.common

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import okio.Path

/** Okio가 제공하지 않는 프로세스 간 잠금을 감싸는 Java NIO 래퍼입니다. */
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

    /** 다른 프로세스가 잠금을 보유하고 있으면 `null`을 반환합니다. */
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
