package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class Sample(
    val value: Any,
    val type: KType,
)

@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T : Any> sampleOf(value: T): Sample =
    Sample(
        value = value,
        type = typeOf<T>(),
    )
