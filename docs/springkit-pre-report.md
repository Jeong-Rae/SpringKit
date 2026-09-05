# Spring Initializr setup/init 내부 구조 분석

> 조사 기준: `spring-io/initializr` `main` branch의 commit `e90b930404033108e1477f495e4db26057e7e67c` (2026-08-27)  
> Repository: https://github.com/spring-io/initializr  
> 목적: Spring 자체의 DI/annotation을 복제하는 것이 아니라, 프로젝트 초기화 요청을 정규화하고 정책을 적용한 뒤 여러 작은 생성 작업으로 materialize하는 메커니즘을 추출한다.

## 0. 핵심 결론

Spring Initializr의 생성 코어는 **“Request → Description → generation context → ordered contributors → project directory”**라는 구조다.

### SpringKit에 적용하는 언어 정책

이 문서가 분석한 upstream Spring Initializr의 핵심 구현 파일은 Java source이며, 아래 링크는 그 조사 사실을 보존한다. SpringKit은 이 구현 언어를 복제하지 않고 메커니즘만 추출하며, 내부 코드를 Kotlin으로 구현한다.

- Java식 interface는 Kotlin `fun interface` 또는 일반 interface로 모델링한다.
- DTO와 불변 모델은 Kotlin `data class`와 `value class`를 우선한다.
- static utility와 holder class는 top-level function, extension function, object 중 소유권을 가장 명확히 표현하는 형태로 전환한다.
- nullability와 불변성은 Kotlin type system으로 표현하고, Java 구현의 방어적 ceremony를 그대로 옮기지 않는다.
- 이 Kotlin-native 적용 정책은 Java 형태로 설명된 아래 pseudo code와 참고 구조보다 우선하되, 분석에서 추출한 순서·생명주기·실패 전파 contract는 보존한다.

중요한 점은 `ProjectGenerator`가 build file, source file, resource를 직접 생성하지 않는다는 것이다. `ProjectGenerator`는 한 번의 생성 작업에 사용할 독립적인 generation context를 만들고, description customizer를 순서대로 적용한 뒤, 해당 description에 맞는 `ProjectContributor`들을 활성화한다. 실제 filesystem 변경은 `DefaultProjectAssetGenerator`가 수집한 ordered `ProjectContributor`들이 하나의 project root에 순차적으로 수행한다.

Spring의 ApplicationContext는 이 분석에서 다음 역할로 환원할 수 있다.

```text
Framework implementation
per-generation ApplicationContext + conditional beans + ordered BeanProvider

Language-free mechanism
한 생성 요청에 필요한 policy/action registry를 만들고
description에 따라 적용 가능한 action을 선택한 뒤
정렬하여 실행한다.
```

따라서 핵심은 DI container가 아니라 **per-generation registry + policy customization + ordered contribution pipeline**이다.

---

## 1. 실행 흐름 관점

### 1.1 HTTP 진입점

HTTP 요청은 [`ProjectGenerationController`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-web/src/main/java/io/spring/initializr/web/controller/ProjectGenerationController.java)에서 project request로 수집된다.

Controller의 책임은 transport adapter 수준이다. HTTP parameter와 media type을 받고 `ProjectGenerationInvoker`에 생성을 위임한다. 프로젝트를 어떻게 구성하거나 파일을 어떻게 쓰는지는 알지 않는다.

핵심 application-level 진입점은 [`ProjectGenerationInvoker`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-web/src/main/java/io/spring/initializr/web/project/ProjectGenerationInvoker.java)다.

Invoker는 다음 경계를 연결한다.

```text
HTTP ProjectRequest
        ↓
ProjectRequestToDescriptionConverter
        ↓
ProjectDescription
        ↓
ProjectGenerator
        ↓
generated directory
        ↓
archive / structure response
```

archive endpoint에서는 생성된 디렉터리를 archive asset으로 포장하고 generation result를 publish한다. 이 객체 역시 build/source 생성 규칙을 소유하지 않는다.

### 1.2 Request → canonical Description

[`DefaultProjectRequestToDescriptionConverter`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-web/src/main/java/io/spring/initializr/web/project/DefaultProjectRequestToDescriptionConverter.java)는 외부 request를 generator가 소비하는 `ProjectDescription`으로 변환한다.

이 단계에서 확인되는 책임은 다음과 같다.

- request의 group/artifact/name/package/build/language/version 등의 값을 internal description으로 이동한다.
- 요청한 Spring Boot version이 metadata에 존재하는지 확인한다.
- dependency ID를 metadata에서 resolve한다.
- 정의되지 않은 dependency를 거부한다.
- 선택한 Spring Boot version과 호환되지 않는 dependency를 거부한다.
- dependency를 canonical collection으로 구성한다.
- application name을 Java source naming 제약에 맞게 다룬다.

즉 이 객체의 역할은 단순 DTO mapper가 아니다.

```text
External vocabulary
        ↓
reference resolution + compatibility validation
        ↓
Canonical project description
```

생성 단계가 문자열 ID나 HTTP parameter를 다시 해석하지 않도록 경계를 만든다.

### 1.3 Default와 convention 적용

[`MetadataProjectDescriptionCustomizer`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-web/src/main/java/io/spring/initializr/web/project/MetadataProjectDescriptionCustomizer.java)는 Initializr metadata를 기반으로 description의 비어 있는 값을 채운다.

확인되는 대표 default는 다음과 같다.

- language
- Spring Boot version
- build system / dialect
- group
- artifact
- version
- name
- description
- package name
- packaging
- Java version

또한 선택한 platform version에 맞는 BOM, dependency, repository 정보를 description에 반영한다.

여기서 중요한 설계는 사용자 입력과 tool policy가 동일한 CLI parser에 섞이지 않는다는 것이다.

```text
User input
    ↓
incomplete Description
    ↓
MetadataProjectDescriptionCustomizer
    ↓
fully specified Description
```

### 1.4 Per-generation plan 조립

핵심 orchestration은 [`ProjectGenerator`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-generator/src/main/java/io/spring/initializr/generator/project/ProjectGenerator.java)에 있다.

실제 코드 흐름은 다음 순서다.

1. description을 사용해 generation context를 새로 만든다.
2. description을 context에 등록한다.
3. context에서 `ProjectDescriptionCustomizer`들을 ordered stream으로 수집하여 description에 적용한다.
4. customizer가 바꾼 최종 description을 다시 context에 등록한다.
5. context를 refresh하여 최종 description 조건에 맞는 generation component를 활성화한다.
6. `ProjectAssetGenerator`에 실제 project 생성을 위임한다.
7. 생성이 끝나면 per-generation context를 닫는다.

이를 framework-free로 환원하면 다음과 같다.

```text
Description
    ↓
new Generation Registry
    ↓
discover ordered Description Policies
    ↓
apply policies
    ↓
freeze/re-register final Description
    ↓
activate applicable Generation Actions
    ↓
execute project materialization
```

특히 customizer 적용 후 description을 다시 등록하고 난 다음 component activation을 수행한다는 순서가 중요하다. **정책 적용 결과가 실행 계획 선택보다 먼저 확정된다.**

### 1.5 실제 파일 생성

[`DefaultProjectAssetGenerator`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-generator/src/main/java/io/spring/initializr/generator/project/DefaultProjectAssetGenerator.java)는 실제 materialization orchestration을 담당한다.

동작은 단순하다.

1. `ProjectDirectoryFactory`로 output directory를 준비한다.
2. generation context에서 모든 `ProjectContributor`를 ordered stream으로 가져온다.
3. 각 contributor에 동일한 `projectRoot`를 전달하여 순차 실행한다.
4. 최종 project root를 반환한다.

Contributor contract는 [`ProjectContributor`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-generator/src/main/java/io/spring/initializr/generator/project/contributor/ProjectContributor.java)에 있다.

개념적으로 다음 정도의 interface다.

```text
GenerationAction:
    contribute(projectRoot)
```

따라서 전체 생성은 하나의 거대한 generator가 아니라 **ordered side-effect actions의 pipeline**이다.

Spring 전용 build 관련 action의 조립은 [`BuildProjectGenerationConfiguration`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-generator-spring/src/main/java/io/spring/initializr/generator/spring/build/BuildProjectGenerationConfiguration.java)에서 확인할 수 있다. 이 구성은 description의 build system, language 등 조건에 따라 필요한 build model/writer/contributor를 generation context에 조립한다.

여기서 framework annotation 자체보다 다음 메커니즘이 중요하다.

```text
Final Description
    ↓
condition evaluation
    ↓
activate Build/Source/Resource generation actions
```

### 1.6 bootstrap / post-processing / verification

현재 정상 생성 경로에서 확인한 범위에서는 다음과 같다.

- dependency는 metadata를 통해 resolve되고 build descriptor에 반영될 generation data로 다뤄진다.
- Initializr 서버가 생성된 프로젝트의 dependency를 실제로 설치하지 않는다.
- runtime 자체를 설치하지 않는다.
- generated project에 대해 최종 `compile`, `test`, `build`를 실행하는 verifier는 이 정상 generation path에서 확인되지 않았다.
- HTTP archive 요청에서는 generation 이후 directory를 ZIP/TAR 등의 asset으로 포장한다.
- generation result publishing은 관찰/telemetry 성격이며 project correctness verification과는 별개다.

따라서 “Ready Project”의 의미는 **파일 구조와 build configuration이 생성되어 반환 가능한 상태**이지, 서버에서 compile/test까지 통과한 상태가 아니다.

---

## 1.7 Language-free 전체 flow

```text
Init/API Request
    ↓
Transport Adapter
    ↓
Request → Description Converter
    ├─ identifier resolution
    ├─ dependency lookup
    └─ compatibility validation
    ↓
Incomplete Project Description
    ↓
Ordered Description Policies
    ├─ defaults
    ├─ version policy
    └─ metadata-derived conventions
    ↓
Final Project Description
    ↓
Per-generation Action Registry
    ↓
Condition-based Action Selection
    ↓
Project Root Provisioning
    ↓
Ordered Contributor Pipeline
    ├─ Build
    ├─ Source
    ├─ Resources
    ├─ Configuration
    └─ Basic project files
    ↓
Generated Project Directory
    ↓
Optional archive packaging
    ↓
Result
```

### 1.8 Orchestration pseudo code

```text
function init(request):
    description = convertAndResolve(request)
    validateReferences(description)

    generation = newGenerationRegistry(description)

    for policy in generation.orderedDescriptionPolicies():
        policy.apply(description)

    generation.activateActionsFor(description)

    root = createProjectRoot()

    for action in generation.orderedProjectActions():
        action.contribute(root)

    return packageIfRequested(root)
```

---

## 2. 객체 책임 관점

### 2.1 책임 관계

```text
ProjectGenerationController
    │ transport
    ▼
ProjectGenerationInvoker
    │ use-case orchestration
    ▼
RequestToDescriptionConverter
    │ canonicalization / reference validation
    ▼
ProjectDescription
    │ generation facts
    ▼
ProjectGenerator
    │ policy application + action assembly
    ▼
DefaultProjectAssetGenerator
    │ filesystem-level pipeline orchestration
    ▼
ProjectContributor*
    ├─ build
    ├─ source
    ├─ config/resource
    └─ basic files
```

### 2.2 핵심 객체별 존재 이유

| 객체 | 책임 | 소유하는 정보 | 알지 않아야 하는 것 |
|---|---|---|---|
| `ProjectGenerationController` | HTTP adapter | request/response protocol | build/source 생성 규칙 |
| `ProjectGenerationInvoker` | generation use-case 연결 | converter, generator, result packaging | 개별 파일 내용 |
| `DefaultProjectRequestToDescriptionConverter` | 외부 입력을 canonical model로 변환 | metadata lookup 규칙 | filesystem |
| `MetadataProjectDescriptionCustomizer` | tool-owned defaults/policy 적용 | Initializr metadata | HTTP, 파일 쓰기 |
| `ProjectDescription` | 생성에 필요한 사실 | project/build/dependency 선택 | contributor discovery |
| `ProjectGenerator` | generation lifecycle orchestration | context factory | 개별 파일 template |
| `DefaultProjectAssetGenerator` | output root와 contributor 실행 | contributor sequence | dependency compatibility 정책 |
| `ProjectContributor` 구현들 | 하나의 project aspect 생성 | 자신에게 필요한 generation model | 전체 HTTP lifecycle |

### 2.3 Orchestration과 작업의 분리

`ProjectGenerator`와 `DefaultProjectAssetGenerator`는 “무엇을 언제 실행할지”를 다루고, contributor는 “project root에 어떤 변화를 줄지”를 다룬다.

이 분리는 다음과 같다.

```text
Orchestrator
    - lifecycle
    - ordering
    - action discovery
    - output root

Worker
    - one generation responsibility
    - concrete files/model rendering
```

이 구조에서는 build generator가 source generator를 직접 호출할 필요가 없다. 둘은 같은 description에서 필요한 data를 받고 동일 root에 독립적으로 기여한다.

### 2.4 Framework 때문에 생긴 객체와 실제 도메인 객체

`ApplicationContext`, bean provider, configuration annotation은 Spring framework 특유의 implementation machinery다. 반면 아래 책임은 framework를 제거해도 남는다.

- `ProjectDescription`: canonical generation input
- `ProjectDescriptionCustomizer`: input policy
- `ProjectContributor`: generation action
- `ProjectAssetGenerator`: action runner
- build/dependency metadata model: version/compatibility policy

따라서 개인용 initializer를 구현할 때 DI container를 도입하지 않아도 같은 구조를 만들 수 있다.

---

## 3. Convention / Policy 적용 관점

### 3.1 Project-specific input

```text
Project-specific input
    ├─ group
    ├─ artifact
    ├─ name
    ├─ package
    ├─ project version
    ├─ language
    ├─ build system
    ├─ packaging
    ├─ Java version
    ├─ Spring Boot version
    └─ selected dependencies
```

사용자가 모든 값을 반드시 지정하는 구조는 아니다.

### 3.2 Tool-owned convention

```text
Tool-owned convention
    ├─ default language
    ├─ default build system
    ├─ default group/artifact/version
    ├─ default package derivation
    ├─ default Java / Spring Boot version
    ├─ dependency compatibility ranges
    ├─ BOM/repository association
    ├─ generated build layout
    ├─ generated source/resource layout
    └─ contributor activation / ordering
```

정책의 중심은 metadata다. version과 dependency compatibility를 template 파일 내부에 분산시키기보다 description을 만들고 보정하는 단계에서 해결한다.

### 3.3 반복 설정을 줄이는 방법

핵심 패턴은 다음 세 가지다.

1. **Sparse input 허용**: 사용자 request가 완전한 project specification일 필요가 없다.
2. **Central policy expansion**: metadata customizer가 빈 값을 일관된 default로 확장한다.
3. **Conditional contribution**: 최종 description에 맞는 generation action만 활성화한다.

이를 일반화하면 다음과 같다.

```text
Small user intent
    ↓
Central convention catalogue
    ↓
Fully specified project description
    ↓
Deterministic generator set
```

---

## 4. Framework-specific mechanism 제거

| Spring 구현 | Language-free mechanism |
|---|---|
| per-generation `ApplicationContext` | 생성 요청 하나에 대한 action/policy registry |
| `BeanProvider.orderedStream()` | 사용 가능한 action을 수집하고 안정된 순서로 실행 |
| conditional configuration | description predicate에 따른 action activation |
| configuration classes | action assembly rules |
| bean lifecycle | generation scope resource lifecycle |

가장 중요한 변환은 다음이다.

```text
ApplicationContext에서 Contributor bean 조회
                ↓
사용 가능한 GenerationAction들을 discovery하고
정해진 순서대로 동일 project root에 적용
```

---

## 5. 소스에서 확인되지 않은 것 / 경계

- 정상 Initializr generation path에서 generated project의 compile/test를 실행하는 final verifier는 확인되지 않았다.
- dependency “resolution”은 project dependency metadata와 compatibility를 결정하는 것이며, 생성 서버가 project dependency를 설치한다는 의미가 아니다.
- 생성 후 Git repository 초기화는 이 핵심 Initializr generation path의 책임으로 확인되지 않았다.
- 특정 IDE integration은 core generator의 핵심 책임이 아니므로 본 분석에서 제외했다.

---

## 6. 먼저 읽어야 할 핵심 코드

1. [`ProjectGenerator.java`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-generator/src/main/java/io/spring/initializr/generator/project/ProjectGenerator.java)  
   generation lifecycle, description customization, per-generation context activation의 중심이다.

2. [`DefaultProjectAssetGenerator.java`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-generator/src/main/java/io/spring/initializr/generator/project/DefaultProjectAssetGenerator.java)  
   실제 contributor pipeline이 얼마나 단순한지 확인할 수 있다.

3. [`ProjectContributor.java`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-generator/src/main/java/io/spring/initializr/generator/project/contributor/ProjectContributor.java)  
   최소 generation action contract를 보여준다.

4. [`ProjectGenerationInvoker.java`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-web/src/main/java/io/spring/initializr/web/project/ProjectGenerationInvoker.java)  
   web transport와 generator core 사이의 application boundary를 보여준다.

5. [`DefaultProjectRequestToDescriptionConverter.java`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-web/src/main/java/io/spring/initializr/web/project/DefaultProjectRequestToDescriptionConverter.java)  
   외부 identifier를 internal generation model로 resolve하는 위치다.

6. [`MetadataProjectDescriptionCustomizer.java`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-web/src/main/java/io/spring/initializr/web/project/MetadataProjectDescriptionCustomizer.java)  
   user input과 tool-owned convention을 분리하는 핵심이다.

7. [`BuildProjectGenerationConfiguration.java`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-generator-spring/src/main/java/io/spring/initializr/generator/spring/build/BuildProjectGenerationConfiguration.java)  
   최종 description에 따라 실제 generation action을 조립하는 방식을 확인할 수 있다.

8. [`ProjectGenerationController.java`](https://github.com/spring-io/initializr/blob/e90b930404033108e1477f495e4db26057e7e67c/initializr-web/src/main/java/io/spring/initializr/web/controller/ProjectGenerationController.java)  
   HTTP 계층이 얼마나 얇게 유지되는지 확인하는 용도다.

---

## 7. 마지막 정리

### 7.1 전체 flow graph

```text
Init Input
    ↓
Request Resolver
    ↓
Project Description
    ↓
Convention / Compatibility Policies
    ↓
Final Description
    ↓
Project Generator
    ├─ action discovery
    ├─ action activation
    └─ action ordering
    ↓
Contributor Pipeline
    ├─ Build
    ├─ Source
    ├─ Resources
    └─ Configuration
    ↓
Ready Project
    ↓
Archive / Response
```

### 7.2 가장 중요한 설계 원칙

1. **외부 request와 canonical generation model을 분리한다.**
2. **default/compatibility policy를 파일 생성보다 먼저 완결한다.**
3. **하나의 generator에 모든 생성을 넣지 않고 작은 ordered contributor로 분해한다.**
4. **한 생성 요청마다 독립적인 action registry/lifecycle을 만든다.**
5. **transport/API와 generator core를 분리한다.**

### 7.3 Framework를 제거한 language-free architecture

```text
Init Input
    ↓
Input Resolver
    ↓
Project Description
    ↓
Policy Engine
    ├─ Defaults
    ├─ Version Compatibility
    └─ Dependency Resolution
    ↓
Generation Plan
    ├─ Build Action
    ├─ Source Action
    ├─ Resource Action
    └─ Config Action
    ↓
Ordered Project Writer
    ↓
Ready Project
```

### 7.4 개인용 opinionated initializer에서 가져갈 부분

가져갈 가치가 큰 부분은 다음과 같다.

- `Input → Description → Plan → Actions`의 명시적인 단계 구분.
- 사용자 입력을 작게 유지하고 중앙 convention이 완전한 description으로 확장하는 구조.
- 파일 단위가 아니라 책임 단위의 contributor.
- action ordering이 필요하면 명시적으로 모델링하는 방식.
- 생성 코어가 CLI/HTTP와 독립적인 구조.

범용 OSS이기 때문에 개인용 도구에서는 줄여도 되는 부분은 다음과 같다.

- per-generation DI container.
- 매우 동적인 conditional bean assembly.
- 범용 metadata schema와 다수 build/language 조합 지원.
- HTTP content negotiation, archive format variation, metadata protocol.
- third-party dependency catalogue의 광범위한 compatibility 모델.

개인용 initializer라면 `GenerationContext`를 일반 객체로 두고 `List<GenerationAction>`을 명시적으로 조립하는 정도로도 핵심 설계는 그대로 유지할 수 있다.
