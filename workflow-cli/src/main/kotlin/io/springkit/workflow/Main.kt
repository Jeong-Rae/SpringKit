package io.springkit.workflow

import com.github.ajalt.clikt.core.main
import io.springkit.workflow.adapter.cli.WorkflowCli
import io.springkit.workflow.runtime.DefaultRuntimeFactory

/** Workflow CLI의 실행 진입점입니다. */
fun main(args: Array<String>) {
  WorkflowCli(DefaultRuntimeFactory.create()).main(args)
}
