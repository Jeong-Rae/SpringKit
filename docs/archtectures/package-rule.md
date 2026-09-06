# 패키지 규칙

기본 패키지는 `me.jeongrae.__PROEJCT__`입니다. `me.jeongrae`는
`jeongrae.me`의 역도메인입니다.

업무 코드는 모듈 역할 아래에서 도메인 기능별로 묶습니다. 기본 패키지
구조는 다음과 같습니다.

```text
me.jeongrae.__PROEJCT__
├── domain
│   └── greeting
├── application
│   └── greeting
│       └── port
├── presentation
│   └── greeting
│       ├── request
│       └── response
└── infrastructure
    ├── greeting
    └── persistence
        └── greeting
```

`domain.<feature>`에는 도메인 모델과 업무 규칙을 둡니다. 도메인 모델은
외부 프레임워크를 참조하지 않습니다.

`application.<feature>`에는 유스케이스를 둡니다. 외부 구현에 필요한
포트는 `application.<feature>.port`에 둡니다.

`presentation.<feature>`에는 요청 처리 코드를 둡니다. 요청과 응답 DTO는
각각 `request`와 `response`에 둡니다.

`infrastructure`에는 `application` 포트의 구현을 둡니다. 영속성 구현은
`infrastructure.persistence.<feature>`에 둡니다. 프레젠테이션 DTO,
영속성 엔티티, 도메인 모델은 서로 다른 타입으로 구분합니다.

모듈 경계를 넘을 때 Spring, Servlet, JPA, Kotlin JDSL과 같은 외부
프레임워크 타입을 `application`과 `domain`에 전달하지 않습니다.
