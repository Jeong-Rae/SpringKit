package com.springkit.agentguidebook.config

import java.nio.file.Path
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.DatabaseConnectionAutoRegistration

/**
 * SQLite Presentation 저장소의 경로와 연결 설정을 보유합니다.
 *
 * 생성할 때 파일 시스템이나 데이터베이스에 접근하지 않습니다.
 */
class SqlitePresentationConfig(
    val databasePath: Path = defaultDatabasePath(),
) {
  /**
   * 실행별 Exposed Database를 연결 설정과 함께 구성합니다.
   *
   * JDBC 연결은 반환된 Database의 transaction이 시작될 때 생성합니다.
   */
  internal fun openDatabase(
      connectionRegistration: DatabaseConnectionAutoRegistration,
  ): Database = throw NotImplementedError("SQLite 연결 설정은 구현 단계에서 추가합니다.")

  private companion object {
    fun defaultDatabasePath(): Path =
        Path.of(
            System.getProperty("user.home"),
            ".adaptive-guidebook",
            "adaptive-guidebook.db",
        )
  }
}
