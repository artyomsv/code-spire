-- What each agent image declares it can run (M3.5 part M).
--
-- A CACHE, and the design depends on it being one. The run worker reads the image and reports; this row
-- is what every screen reads. So no page ever waits on a container runtime or a cluster, and a
-- deployment whose runtime arm is unfinished degrades to the last list it was told rather than to none.
--
-- One row per harness, holding the whole list. The list is only ever read whole and replaced whole, so
-- a row per model would be rows nobody reads alone and a replacement that has to be made atomic by hand.
CREATE TABLE harness_catalogue (
    harness     VARCHAR(64)  PRIMARY KEY,
    -- The exact image reference the answer describes. A different tag of the same repository may carry
    -- a different CLI and a different list, so an answer for an image the harness no longer runs is
    -- discarded rather than served.
    image       TEXT         NOT NULL,
    -- OK / NO_CATALOGUE / UNREADABLE / IMAGE_UNAVAILABLE. Kept beside the list because an empty list
    -- means four different things, and a screen that cannot tell them apart shows an empty dropdown.
    status      VARCHAR(32)  NOT NULL
        CHECK (status IN ('OK','NO_CATALOGUE','UNREADABLE','IMAGE_UNAVAILABLE')),
    -- [{slug, displayName, defaultEffort, efforts[], visible, priority}], empty unless status is OK.
    models      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    observed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
