package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** HTTP 값의 대표 값과 선언된 Kotlin 타입을 함께 보존합니다. */
data class Sample(
    val value: Any,
    val type: KType,
)

/** 구체적인 [T]의 타입 정보를 잃지 않고 [Sample]을 생성합니다. */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T : Any> sampleOf(value: T): Sample =
    Sample(
        value = value,
        type = typeOf<T>(),
    )
