package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.SessionId
import java.time.Instant

class Presentation(
    val sessionId: SessionId,
    val guidelineId: GuidelineId,
    val presentedAt: Instant,
)
