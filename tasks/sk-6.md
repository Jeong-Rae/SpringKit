# sk-6: Dependency 정책

## Goal

외부 의존성 ID를 canonical metadata로 resolve하고 platform 호환성, BOM, repository 정책을 중앙에서 적용한다.

## 선행 Task

- sk-5

## Step

### 1. Dependency metadata 모델 정의

- ID, module 좌표, 지원 platform 범위, 선택적 BOM/repository를 표현한다.
- 검증: metadata 불변 조건과 버전 범위 경계 테스트가 통과한다.
- 커밋: `Feature: 의존성 메타데이터 정의`

### 2. Dependency catalogue 구현

- 지원 dependency를 ID로 조회하는 중앙 catalogue를 추가한다.
- 검증: 알려진 ID 조회와 중복 ID 거부 테스트가 통과한다.
- 커밋: `Feature: 의존성 카탈로그 추가`

### 3. Dependency resolver 구현

- 외부 ID 목록을 canonical 의존성 collection으로 변환한다.
- 검증: 알 수 없는 ID, 중복 선택, 정상 복수 선택 테스트가 통과한다.
- 커밋: `Feature: 프로젝트 의존성 해결`

### 4. Platform 호환성 검증

- 선택한 platform version과 의존성 지원 범위를 비교한다.
- 검증: 하한, 상한, 범위 밖 버전 테스트가 통과한다.
- 커밋: `Feature: 의존성 호환성 검증`

### 5. BOM과 repository 정책 적용

- 의존성 metadata에서 필요한 BOM과 repository를 최종 description에 집계한다.
- 검증: 중복 제거와 안정된 정렬 테스트가 통과한다.
- 커밋: `Feature: 의존성 빌드 메타데이터 해결`

### 6. Gradle contributor 연결

- canonical 의존성, BOM, repository 정보를 build script에 반영한다.
- 검증: 대표 의존성 조합으로 생성된 프로젝트의 Gradle build가 성공한다.
- 커밋: `Feature: 의존성 설정 생성`

## 완료 조건

- 생성 contributor가 raw 의존성 ID를 해석하지 않는다.
- 비호환 의존성은 filesystem 변경 전에 거부된다.
- 지원 조합으로 생성한 프로젝트의 Gradle build가 성공한다.

## 비범위

- artifact 다운로드나 설치
- 범용 원격 metadata protocol
