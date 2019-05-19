object MyKotlinMain {
    @JvmStatic
    fun main(args: Array<String>) {
        println("Hello")
        for (i in 0..1999) {
            helperMethod()
        }
    }

    internal fun factorial(n: Int): Int {
        return if (n < 2) {
            1
        } else n * factorial(n - 1)

    }

    internal fun helperMethod() {
        println(factorial(20))
    }
}
