# 패키지 규칙

기본 패키지는 `me.jeongrae.__PROEJCT__`입니다. `me.jeongrae`는
`jeongrae.me`의 역도메인입니다.

업무 코드는 기술 종류보다 도메인 기능을 우선하여 묶습니다. 각 기능
안에서는 코드가 속한 모듈의 역할을 따릅니다.

Presentation DTO, persistence entity, domain model은 서로 다른 타입으로
구분합니다.

모듈 경계를 넘을 때 Spring, Servlet, JPA, Kotlin JDSL과 같은 외부
프레임워크 타입을 `application`과 `domain`에 전달하지 않습니다.
