public class MyMain {
    public static void main(String[] args) {
        System.out.println("Hello");
        for (int i = 0; i < 10000; i++) {
            helperMethod();
        }
    }

    static int factorial(int n) {
        if (n < 2) {
            return 1;
        }

        return n * factorial(n - 1);
    }

    static void helperMethod() {
        System.out.println(factorial(20));
    }
}
