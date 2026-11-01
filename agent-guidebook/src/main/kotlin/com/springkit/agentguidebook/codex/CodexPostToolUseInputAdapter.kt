package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import com.springkit.agentguidebook.models.SessionId
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.LinkedHashSet
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CodexPostToolUseInputAdapter {
  fun read(input: String): GuidelinePresentationRequest? {
    val inputObject = parseInputObject(input)
    val hookEventName = requiredString(inputObject, "hook_event_name")
    val toolName = requiredString(inputObject, "tool_name")

    if (hookEventName != POST_TOOL_USE_EVENT || toolName != APPLY_PATCH_TOOL) {
      return null
    }

    val sessionId = sessionId(requiredString(inputObject, "session_id"))
    val cwd = absolutePath(requiredString(inputObject, "cwd"), "cwd")
    val toolResponse = requiredString(inputObject, "tool_response")
    val changes = parseChanges(toolResponse) ?: return null
    val filepaths = existingFilepaths(cwd, changes)

    if (filepaths.isEmpty()) {
      return null
    }

    return GuidelinePresentationRequest(sessionId, cwd, filepaths)
  }

  private fun parseInputObject(input: String): JsonObject {
    val element =
        try {
          Json.parseToJsonElement(input)
        } catch (cause: SerializationException) {
          invalidInput("Codex Hook 입력 JSON을 해석할 수 없습니다.", cause)
        }

    return element as? JsonObject ?: invalidInput("Codex Hook 입력은 JSON 객체여야 합니다.")
  }

  private fun requiredString(input: JsonObject, name: String): String {
    val value = input[name] as? JsonPrimitive

    if (value == null || !value.isString) {
      invalidInput("Codex Hook 입력의 $name 필드는 문자열이어야 합니다.")
    }

    return value.content
  }

  private fun sessionId(value: String): SessionId =
      try {
        SessionId(value)
      } catch (cause: IllegalArgumentException) {
        invalidInput("Codex Hook 입력의 session_id 필드가 비어 있습니다.", cause)
      }

  private fun absolutePath(value: String, fieldName: String): Path {
    val path =
        try {
          Path.of(value)
        } catch (cause: InvalidPathException) {
          invalidInput("Codex Hook 입력의 $fieldName 경로가 올바르지 않습니다.", cause)
        }

    if (!path.isAbsolute) {
      invalidInput("Codex Hook 입력의 $fieldName 경로는 절대 경로여야 합니다.")
    }

    return path
  }

  private fun parseChanges(toolResponse: String): List<Pair<Char, Path>>? {
    val lines = responseLines(toolResponse)
    val successMarkerIndex = lines.indexOf(SUCCESS_MARKER)
    val envelopeLines = if (successMarkerIndex < 0) lines else lines.take(successMarkerIndex)

    if (envelopeLines.any(::isNonzeroExitCode)) {
      return null
    }

    if (successMarkerIndex < 0) {
      return null
    }

    return lines.drop(successMarkerIndex + 1).map(::parseChangeLine)
  }

  private fun responseLines(toolResponse: String): List<String> {
    val lines = toolResponse.split('\n').map { line -> line.removeSuffix("\r") }
    return if (toolResponse.endsWith('\n')) lines.dropLast(1) else lines
  }

  private fun isNonzeroExitCode(line: String): Boolean {
    if (!line.startsWith(EXIT_CODE_PREFIX)) {
      return false
    }

    val exitCode = line.removePrefix(EXIT_CODE_PREFIX).trim().toIntOrNull()
    return exitCode != null && exitCode != 0
  }

  private fun parseChangeLine(line: String): Pair<Char, Path> {
    if (line.length < 3 || line[0] !in CHANGE_STATUSES || line[1] != ' ') {
      invalidInput("Codex Hook 입력의 변경 파일 목록 형식이 올바르지 않습니다.")
    }

    val pathValue = line.substring(2)

    if (pathValue.isEmpty()) {
      invalidInput("Codex Hook 입력의 변경 파일 경로가 비어 있습니다.")
    }

    val path =
        try {
          Path.of(pathValue)
        } catch (cause: InvalidPathException) {
          invalidInput("Codex Hook 입력의 변경 파일 경로가 올바르지 않습니다.", cause)
        }

    return line[0] to path
  }

  private fun existingFilepaths(
      cwd: Path,
      changes: List<Pair<Char, Path>>,
  ): List<Path> {
    val normalizedPaths = LinkedHashSet<Path>()

    changes.forEach { (status, path) ->
      if (status != DELETED_STATUS) {
        val absolutePath = if (path.isAbsolute) path else cwd.resolve(path)
        normalizedPaths.add(absolutePath.toAbsolutePath().normalize())
      }
    }

    return buildList {
      normalizedPaths.forEach { path ->
        val attributes =
            try {
              Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (_: NoSuchFileException) {
              return@forEach
            }

        if (!attributes.isDirectory) {
          add(path)
        }
      }
    }
  }

  private fun invalidInput(
      message: String,
      cause: Throwable? = null,
  ): Nothing = throw InvalidCodexHookInputException(message, cause)

  private companion object {
    const val POST_TOOL_USE_EVENT = "PostToolUse"
    const val APPLY_PATCH_TOOL = "apply_patch"
    const val EXIT_CODE_PREFIX = "Exit code:"
    const val SUCCESS_MARKER = "Success. Updated the following files:"
    const val CHANGE_STATUSES = "AMD"
    const val DELETED_STATUS = 'D'
  }
}
