package dev.codespire.scm.bitbucket;

/** Non-2xx response from the Bitbucket API. 404 on a diff means the commit was force-pushed away. */
public class BitbucketApiException extends RuntimeException {

    private final int status;

    public BitbucketApiException(int status, String method, String path) {
        super("Bitbucket API " + method + " " + path + " failed with HTTP " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public boolean isNotFound() {
        return status == 404;
    }
}
