# 패키지 규칙

기본 패키지는 `me.jeongrae.__PROEJCT__`입니다. 서비스 도메인
`jeongrae.me`를 Java 패키지 표기법에 따라 역순으로 배치하고 프로젝트
식별자를 마지막에 붙입니다. `nook`은 프로젝트 식별자의 예시이며 고정값이
아닙니다.

업무 코드는 기술 종류보다 도메인 기능을 우선하여 묶습니다. 각 기능
안에서는 코드가 속한 모듈의 역할을 따릅니다.

```text
me.jeongrae.__PROEJCT__
└── order
    ├── presentation
    │   └── dto
    ├── application
    │   └── port
    ├── domain
    ├── infrastructure
    │   └── persistence
    │       └── entity
    ├── batch
    └── worker
```

예를 들어 주문 기능은 `me.jeongrae.__PROEJCT__.order` 아래에 둡니다.
`me.jeongrae.__PROEJCT__.presentation.order`처럼 기술 역할을 먼저 나누지
않습니다.

Presentation DTO, persistence entity, domain model은 서로 다른 타입으로
구분합니다. Presentation DTO는 요청과 응답 형식을 나타냅니다. Persistence
entity는 데이터베이스 저장 형식을 나타냅니다. Domain model은 업무 규칙과
상태를 나타냅니다. 각 모듈의 경계에서 역할에 맞는 타입으로 변환합니다.

Application 포트는 `application`에 둡니다. 포트 구현과 persistence entity와
domain model 사이의 변환은 `infrastructure`에 둡니다.

모듈 경계를 넘을 때 Spring, Servlet, JPA, Kotlin JDSL과 같은 외부
프레임워크 타입을 `application`과 `domain`에 전달하지 않습니다. 해당
모듈의 공개 타입과 메서드 시그니처는 Kotlin과 프로젝트 내부 타입만
사용합니다.
