package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.models.GuidelineId
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8

class CodexHookOutputTest :
    FeatureSpec({
      feature("Codex Hook 출력 포트") {
        scenario("정상 응답을 UTF-8 바이트 그대로 stdout에 쓰고 flush하며 스트림을 닫지 않습니다.") {
          val stdout = PortTrackingOutputStream()
          val stderr = PortTrackingOutputStream()
          val guidelines = listOf(MatchedGuideline(GuidelineId("style-rule"), "스타일 지침입니다."))
          val expected =
              checkNotNull(CodexPostToolUseOutputAdapter().render(guidelines)).toByteArray(UTF_8)

          CodexHookOutput(stdout, stderr, CodexPostToolUseOutputAdapter()).present(guidelines)

          stdout.bytes.toList() shouldBe expected.toList()
          stdout.flushCount shouldBe 1
          stdout.flushSnapshots.map { it.toList() } shouldBe listOf(expected.toList())
          stderr.writeCount shouldBe 0
          stderr.flushCount shouldBe 0
          stdout.closed shouldBe false
          stderr.closed shouldBe false
        }

        scenario("렌더링 결과가 null이면 두 스트림에 쓰거나 flush하지 않고 닫지 않습니다.") {
          val stdout = PortTrackingOutputStream()
          val stderr = PortTrackingOutputStream()

          CodexHookOutput(stdout, stderr, CodexPostToolUseOutputAdapter()).present(emptyList())

          stdout.writeCount shouldBe 0
          stdout.flushCount shouldBe 0
          stderr.writeCount shouldBe 0
          stderr.flushCount shouldBe 0
          stdout.closed shouldBe false
          stderr.closed shouldBe false
        }

        scenario("stdout 쓰기 IOException을 같은 인스턴스로 전달하고 stderr로 우회하지 않습니다.") {
          val failure = IOException("stdout 쓰기 실패")
          val stdout = PortFailingWriteOutputStream(failure)
          val stderr = PortTrackingOutputStream()

          val caught =
              try {
                CodexHookOutput(stdout, stderr, CodexPostToolUseOutputAdapter())
                    .present(sampleGuidelines)
                null
              } catch (exception: IOException) {
                exception
              }

          caught shouldBe failure
          stderr.writeCount shouldBe 0
          stderr.flushCount shouldBe 0
          stdout.closed shouldBe false
          stderr.closed shouldBe false
        }

        scenario("stdout flush IOException을 같은 인스턴스로 전달하고 stderr로 우회하지 않습니다.") {
          val failure = IOException("stdout flush 실패")
          val stdout = PortFailingFlushOutputStream(failure)
          val stderr = PortTrackingOutputStream()
          val expected =
              checkNotNull(CodexPostToolUseOutputAdapter().render(sampleGuidelines))
                  .toByteArray(UTF_8)

          val caught =
              try {
                CodexHookOutput(stdout, stderr, CodexPostToolUseOutputAdapter())
                    .present(sampleGuidelines)
                null
              } catch (exception: IOException) {
                exception
              }

          caught shouldBe failure
          stdout.bytes.toList() shouldBe expected.toList()
          stdout.flushSnapshots.map { it.toList() } shouldBe listOf(expected.toList())
          stderr.writeCount shouldBe 0
          stderr.flushCount shouldBe 0
          stdout.closed shouldBe false
          stderr.closed shouldBe false
        }

        scenario("diagnose는 UTF-8 메시지와 한 줄바꿈을 stderr에만 쓰고 flush합니다.") {
          val stdout = PortTrackingOutputStream()
          val stderr = PortTrackingOutputStream()
          val message = "저장소 연결 실패"
          val expected = (message + "\n").toByteArray(UTF_8)

          CodexHookOutput(stdout, stderr, CodexPostToolUseOutputAdapter()).diagnose(message)

          stderr.bytes.toList() shouldBe expected.toList()
          stderr.flushCount shouldBe 1
          stderr.flushSnapshots.map { it.toList() } shouldBe listOf(expected.toList())
          stdout.writeCount shouldBe 0
          stdout.flushCount shouldBe 0
          stdout.closed shouldBe false
          stderr.closed shouldBe false
        }

        scenario("stderr 쓰기 IOException은 내부 처리하고 Error는 전파하며 stdout을 건드리지 않습니다.") {
          val writeFailure = IOException("stderr 쓰기 실패")
          val stdout = PortTrackingOutputStream()
          val stderr = PortFailingWriteOutputStream(writeFailure)

          CodexHookOutput(stdout, stderr, CodexPostToolUseOutputAdapter()).diagnose("진단")

          stderr.writeCount shouldBeGreaterThan 0
          stderr.flushCount shouldBe 0
          stdout.writeCount shouldBe 0
          stdout.flushCount shouldBe 0

          val error = AssertionError("stderr Error")
          val errorOutput = PortFailingWriteErrorOutputStream(error)
          val propagated =
              try {
                CodexHookOutput(stdout, errorOutput, CodexPostToolUseOutputAdapter()).diagnose("진단")
                null
              } catch (failure: AssertionError) {
                failure
              }

          propagated shouldBe error
          stdout.closed shouldBe false
          stderr.closed shouldBe false
          errorOutput.closed shouldBe false
        }

        scenario("stderr flush IOException은 내부 처리하고 stdout으로 우회하지 않으며 스트림을 닫지 않습니다.") {
          val stdout = PortTrackingOutputStream()
          val stderr = PortFailingFlushOutputStream(IOException("stderr flush 실패"))
          val message = "진단 실패"
          val expected = (message + "\n").toByteArray(UTF_8)

          CodexHookOutput(stdout, stderr, CodexPostToolUseOutputAdapter()).diagnose(message)

          stderr.bytes.toList() shouldBe expected.toList()
          stderr.flushCount shouldBe 1
          stderr.flushSnapshots.map { it.toList() } shouldBe listOf(expected.toList())
          stdout.writeCount shouldBe 0
          stdout.flushCount shouldBe 0
          stdout.closed shouldBe false
          stderr.closed shouldBe false
        }
      }
    })

private open class PortTrackingOutputStream : OutputStream() {
  private val output = ByteArrayOutputStream()
  var writeCount = 0
    private set

  var flushCount = 0
    private set

  private val capturedFlushes = mutableListOf<ByteArray>()
  val flushSnapshots: List<ByteArray>
    get() = capturedFlushes.toList()

  var closed = false
    private set

  val bytes: ByteArray
    get() = output.toByteArray()

  override fun write(value: Int) {
    writeCount += 1
    output.write(value)
  }

  override fun write(
      bytes: ByteArray,
      offset: Int,
      length: Int,
  ) {
    writeCount += 1
    output.write(bytes, offset, length)
  }

  override fun flush() {
    flushCount += 1
    capturedFlushes += output.toByteArray()
  }

  override fun close() {
    closed = true
  }
}

private class PortFailingWriteOutputStream(private val failure: IOException) : OutputStream() {
  var writeCount = 0
    private set

  var flushCount = 0
    private set

  var closed = false
    private set

  override fun write(value: Int) {
    writeCount += 1
    throw failure
  }

  override fun write(
      bytes: ByteArray,
      offset: Int,
      length: Int,
  ) {
    writeCount += 1
    throw failure
  }

  override fun flush() {
    flushCount += 1
  }

  override fun close() {
    closed = true
  }
}

private class PortFailingFlushOutputStream(private val failure: IOException) :
    PortTrackingOutputStream() {
  override fun flush() {
    super.flush()
    throw failure
  }
}

private class PortFailingWriteErrorOutputStream(private val failure: AssertionError) :
    OutputStream() {
  var closed = false
    private set

  override fun write(value: Int) {
    throw failure
  }

  override fun close() {
    closed = true
  }
}

private val sampleGuidelines = listOf(MatchedGuideline(GuidelineId("style-rule"), "스타일 지침입니다."))
