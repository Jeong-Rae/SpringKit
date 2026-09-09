package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals

class SampleTest {

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `primitive의 값과 타입을 보존한다`() {
        val sample = sampleOf(1L)

        assertEquals(1L, sample.value)
        assertEquals(typeOf<Long>(), sample.type)
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `enum의 값과 타입을 보존한다`() {
        val sample = sampleOf(UserRole.ADMIN)

        assertEquals(UserRole.ADMIN, sample.value)
        assertEquals(typeOf<UserRole>(), sample.type)
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `generic collection의 원소 타입을 보존한다`() {
        val sample = sampleOf(listOf("USER", "ADMIN"))

        assertEquals(listOf("USER", "ADMIN"), sample.value)
        assertEquals(typeOf<List<String>>(), sample.type)
    }

    private enum class UserRole {
        USER,
        ADMIN,
    }
}
