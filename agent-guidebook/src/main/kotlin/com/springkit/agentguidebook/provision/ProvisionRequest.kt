package com.springkit.agentguidebook.provision

import java.nio.file.Path
import java.util.Collections

class ProvisionRequest(
    val sessionId: SessionId,
    val cwd: Path,
    filepaths: Collection<Path>,
) {
  val filepaths: List<Path> = Collections.unmodifiableList(ArrayList(filepaths))
}
