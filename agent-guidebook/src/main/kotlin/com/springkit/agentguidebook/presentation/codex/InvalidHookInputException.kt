package com.springkit.agentguidebook.presentation.codex

class InvalidHookInputException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
