package dev.codespire.orchestrator.settings;

/** The real {@link ReviewDiffLimit} over a given repository, for tests outside this package. */
public final class ReviewDiffLimitFixture {

    private ReviewDiffLimitFixture() {
    }

    public static ReviewDiffLimit over(AppSettingRepository settings) {
        ReviewDiffLimit limit = new ReviewDiffLimit();
        limit.settings = settings;
        return limit;
    }
}
