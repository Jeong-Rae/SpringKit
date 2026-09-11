package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** HTTP 값의 대표 값과 선언된 Kotlin 타입 보존 */
data class Sample(
    val value: Any,
    val type: KType,
)

/** 구체적인 [T] 타입 정보를 보존하는 [Sample] 생성 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T : Any> sampleOf(value: T): Sample =
    Sample(
        value = value,
        type = typeOf<T>(),
    )
