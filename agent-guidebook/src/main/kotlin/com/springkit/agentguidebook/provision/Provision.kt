package com.springkit.agentguidebook.provision

import com.springkit.agentguidebook.domain.GuidelineId
import java.time.Instant

class Provision(
    val sessionId: SessionId,
    val guidelineId: GuidelineId,
    val providedAt: Instant,
)
