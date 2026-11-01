package com.springkit.agentguidebook.codex

class InvalidCodexHookInputException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
