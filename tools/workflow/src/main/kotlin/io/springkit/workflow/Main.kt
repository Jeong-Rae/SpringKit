package io.springkit.workflow

import com.github.ajalt.clikt.core.main
import io.springkit.workflow.adapter.cli.WorkflowCli
import io.springkit.workflow.adapter.cli.WorkflowCommandGateway
import io.springkit.workflow.adapter.json.encodeToString
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.runtime.DefaultRuntimeFactory
import io.springkit.workflow.runtime.WorkflowConfigurationException
import java.io.PrintStream
import kotlin.system.exitProcess

/*
 * Workflow CLI의 실행 진입점입니다.
 */
fun main(args: Array<String>): Unit =
    runWorkflow(
        args = args,
        stdout = System.out,
        stderr = System.err,
        runtimeFactory = { DefaultRuntimeFactory.create() },
    )

/*
 * 실행 환경을 준비한 뒤 Clikt 명령을 시작하고 구성 오류를 공통 결과로 변환합니다.
 */
internal fun runWorkflow(
    args: Array<String>,
    stdout: PrintStream,
    stderr: PrintStream,
    runtimeFactory: () -> WorkflowCommandGateway,
    exit: (Int) -> Nothing = ::exitProcess,
) {
  try {
    WorkflowCli(runtimeFactory(), stdout, stderr).main(args)
  } catch (failure: WorkflowConfigurationException) {
    val result =
        WorkflowResult.Failure(
            FailureData(
                code = FailureCode.INVALID_ARGUMENT,
                message = failure.message ?: "Workflow 실행 환경 설정이 올바르지 않습니다.",
            )
        )
    if (args.any { it == "--json" }) stdout.println(result.encodeToString())
    stderr.println("workflow 실행 환경 설정을 확인하지 못했습니다: ${result.data.message}")
    exit(1)
  }
}
