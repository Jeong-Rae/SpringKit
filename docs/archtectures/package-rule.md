# 패키지 규칙

기본 패키지는 `me.jeongrae.__PROJECT__`입니다. 프로젝트는 멀티모듈을 기본으로 구성하며, 각 모듈 안에서는 기술 종류보다 도메인 기능을 우선하여 패키지를 나눕니다.

## BC와 Slice

BC(Bounded Context)는 모델과 유비쿼터스 언어의 소유 경계입니다. Slice는 유스케이스 실행과 변경을 묶는 경계입니다. Entity나 UseCase 하나마다 BC를 만들지 않습니다.

BC 안에서는 기술 계층보다 업무 Slice를 먼저 배치합니다. 여러 Slice가 공유하는 일관된 도메인 모델과 규칙은 BC의 `domain`이 소유합니다. 특정 Slice의 요청, Session, Port는 그 Slice가 소유합니다. `domain`을 전역 `core`나 `util` 저장소로 사용하지 않습니다.

멀티모듈 템플릿의 `presentation`, `application`, `domain`, `infrastructure`는 계층별 책임을 나타냅니다. 요청·응답, 유스케이스와 Port, 도메인 규칙, 외부 연동은 실제로 필요한 범위에서 해당 Slice에 둡니다. 멀티모듈의 계층 구성은 [모듈 구조](module-structure.md)를 따릅니다.

Agent Guidebook에서 `com.springkit.agentguidebook.domain`은 Guideline 모델과 순수 검색 정책을 소유하고, `search`와 `presentation`은 각각 검색과 지침 제공 Slice를 소유합니다. 예를 들어 Session과 제공 요청·Port는 `presentation`에, 파일 시스템 Adapter는 `search.filesystem`에 둡니다.

`batch`는 정기·대량 작업, `worker`는 비동기 작업을 실행합니다.

프레젠테이션 DTO, 영속성 엔티티, 도메인 모델을 서로 구분합니다. 모듈 경계를 넘을 때 외부 프레임워크 타입을 `application`과 `domain`에 전달하지 않습니다.
