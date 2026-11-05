package com.springkit.agentguidebook.search

import com.springkit.agentguidebook.search.filesystem.FileSystemInputReader

object Factory {
  fun fileSystem(): SearchGuidelinesUseCase = SearchGuidelinesUseCase(FileSystemInputReader())
}
