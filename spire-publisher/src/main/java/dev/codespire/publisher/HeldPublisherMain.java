package dev.codespire.publisher;

/** Distinct executable: an older publisher image must fail to start, never fall back to pushing. */
public final class HeldPublisherMain {
    private HeldPublisherMain() {}

    public static void main(String[] args) throws Exception {
        PublisherMain.run(PublicationPolicy.Mode.HELD);
    }
}
