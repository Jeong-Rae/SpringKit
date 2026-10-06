package com.springkit.agentguidebook

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BuildConfigurationTest :
    FunSpec({
      test("Kotest를 Java 25 toolchain에서 실행한다") {
        Runtime.version().feature() shouldBe 25
      }
    })
