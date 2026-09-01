package dev.codespire.workspace;

/** An agent can write an object bomb. An unbounded read is a denial of service on the publisher. */
public class BundleTooLargeException extends RuntimeException {

    public BundleTooLargeException(long actual, long max) {
        super("bundle is " + actual + " bytes, cap is " + max);
    }
}
