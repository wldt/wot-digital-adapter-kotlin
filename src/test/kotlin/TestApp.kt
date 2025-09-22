import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeExactly

class TestApp :
    StringSpec({
        "Simple test" {
            42.shouldBeExactly(42)
        }
    })
