package com.example.factor;

public class FactorizerTest {

    private static int strideFor(int value) {
        return value % 2 == 0 ? 2 : System.getProperty("stride") == null
                ? 0
                : Integer.parseInt(System.getProperty("stride"));
    }

    public int factorize(int value) {
        return new Factorizer(strideFor(value)).smallestFactor(value);
    }
}
