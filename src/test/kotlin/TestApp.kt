import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeExactly

class TestApp :
    StringSpec({
        "Test the sample function" {
            sampleFunction()
            42.shouldBeExactly(42)
        }
    })
