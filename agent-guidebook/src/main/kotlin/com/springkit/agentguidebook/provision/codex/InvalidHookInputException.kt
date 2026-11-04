package com.springkit.agentguidebook.provision.codex

class InvalidHookInputException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
