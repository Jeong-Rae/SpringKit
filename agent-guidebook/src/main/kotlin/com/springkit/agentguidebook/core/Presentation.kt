package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.SessionId
import java.time.Instant

/**
 * 특정 Session에 지침을 제공한 사실과 UTC 시각을 나타냅니다.
 *
 * 한 Session에서 지침을 다시 제공할 수 있는지는 Session과 Guideline ID로 판단합니다.
 */
class Presentation(
    val sessionId: SessionId,
    val guidelineId: GuidelineId,
    val presentedAt: Instant,
)
