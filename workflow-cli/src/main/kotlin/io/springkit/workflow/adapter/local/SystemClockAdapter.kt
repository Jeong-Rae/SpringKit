package io.springkit.workflow.adapter.local

import io.springkit.workflow.application.ClockPort
import io.springkit.workflow.application.NowRequest
import io.springkit.workflow.application.NowResponse
import io.springkit.workflow.application.PortResult

/** 시스템 시계를 애플리케이션 [ClockPort]로 감싸는 adapter입니다. */
class SystemClockAdapter(private val epochMillis: () -> Long = System::currentTimeMillis) :
    ClockPort {
  override fun now(request: NowRequest): PortResult<NowResponse> =
      PortResult.Success(NowResponse(epochMillis()))
}

/** 로컬 실행 시계의 의미를 드러내는 호환 별칭입니다. */
typealias LocalClockAdapter = SystemClockAdapter
