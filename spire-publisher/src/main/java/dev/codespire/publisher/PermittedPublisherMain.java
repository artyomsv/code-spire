package dev.codespire.publisher;

/** Runs only the publisher over the retained handoff, under trusted process environment authority. */
public final class PermittedPublisherMain {
    private PermittedPublisherMain() {}

    public static void main(String[] args) throws Exception {
        PublisherMain.run(PublicationPolicy.Mode.PERMITTED);
    }
}
