package com.springkit.agentguidebook.search

import java.nio.file.Path
import java.util.ArrayList
import java.util.Collections

class SearchRequest(
    val cwd: Path,
    filepaths: Collection<Path>,
) {
  val filepaths: List<Path> = Collections.unmodifiableList(ArrayList(filepaths))
}
