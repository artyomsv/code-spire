package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.HarnessImageCommand;
import dev.codespire.contract.event.HarnessImageResult;
import dev.codespire.orchestrator.pipeline.KafkaSends;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which models each harness can run, as its agent image declares them (M3.5 part M).
 *
 * <p>The orchestrator asks and remembers; it never reads an image itself. It has no container runtime,
 * and on Kubernetes it never will, so the run worker — which must reach every agent image or no run could
 * start — reads the label and reports. What comes back is cached in {@code harness_catalogue}, and every
 * screen reads the cache: no page waits on a runtime, and an unfinished runtime arm degrades to the last
 * answer rather than to none.
 */
@ApplicationScoped
public class HarnessCatalogues {

    private static final Logger LOG = Logger.getLogger(HarnessCatalogues.class);

    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;
    @Inject FactoryConfig config;

    @Inject @Channel("harness-image-commands-out")
    Emitter<HarnessImageCommand> commands;

    /** What a screen shows for one harness. */
    public record Catalogue(String harness, String image, HarnessImageResult.Status status,
                            List<HarnessImageResult.Model> models, Instant observedAt) {

        /** The models to OFFER: the ones the vendor wants shown, in the vendor's own order. */
        public List<HarnessImageResult.Model> offered() {
            return models.stream().filter(HarnessImageResult.Model::visible)
                    .sorted(Comparator.comparingInt(HarnessImageResult.Model::priority)).toList();
        }

        /** Whether this harness can run the model, whether or not the vendor offers it. */
        public Optional<HarnessImageResult.Model> find(String slug) {
            return models.stream().filter(model -> model.slug().equals(slug)).findFirst();
        }
    }

    /** The refresh schedule, read so that turning it off also turns off the ask at startup. */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "spire.harness-catalogue-interval", defaultValue = "10m")
    String refreshEvery;

    void onStart(@Observes StartupEvent event) {
        // "off" means off: a schedule switched off that still asked once per boot would put a broker
        // send into every test class's startup, for an answer nothing in that test reads.
        if (!"off".equalsIgnoreCase(refreshEvery)) refresh();
    }

    /**
     * Asks again, for every configured harness.
     *
     * <p>On a schedule as well as at startup, because an image can be rebuilt under the same tag, and
     * because a worker that was down at startup would otherwise leave the cache empty until the next
     * orchestrator restart. The answer is cheap: a label read, and a pull only the first time.
     */
    @Scheduled(every = "${spire.harness-catalogue-interval:10m}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refresh() {
        for (Map.Entry<String, String> harness : config.agentImage().entrySet()) {
            try {
                KafkaSends.sendAndAwait(commands, harness.getKey(),
                        new HarnessImageCommand.Describe(UUID.randomUUID().toString(), harness.getKey(), harness.getValue()),
                        "describe the image for " + harness.getKey());
            } catch (RuntimeException undelivered) {
                // The next interval asks again; one lost question is not worth failing startup over.
                LOG.warnf("could not ask for the model catalogue of %s (%s)", harness.getKey(),
                        undelivered.getClass().getSimpleName());
            }
        }
    }

    /**
     * Stores an answer — unless it describes an image the harness no longer runs.
     *
     * <p>A different tag of the same repository may carry a different CLI and a different list, so an
     * answer that arrives after the configuration moved on would describe models that will not run.
     */
    public void record(HarnessImageResult.Described answer) {
        String current = config.agentImage().get(answer.harness());
        if (current == null || !current.equals(answer.image())) {
            LOG.infof("ignoring a model catalogue for %s that describes an image it no longer runs", answer.harness());
            return;
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO harness_catalogue (harness, image, status, models, observed_at)
                VALUES (?, ?, ?, ?::jsonb, now())
                ON CONFLICT (harness) DO UPDATE
                   SET image=excluded.image, status=excluded.status, models=excluded.models, observed_at=now()
                """)) {
            ps.setString(1, answer.harness());
            ps.setString(2, answer.image());
            ps.setString(3, answer.status().name());
            ps.setString(4, mapper.writeValueAsString(answer.models()));
            ps.executeUpdate();
        } catch (SQLException | JsonProcessingException failure) {
            throw new IllegalStateException("The model catalogue for " + answer.harness() + " could not be stored", failure);
        }
    }

    /**
     * Why this harness cannot run the model at this level, or empty when it can — or when nobody can
     * know, which lets a model through and refuses a level (design §6A.4a).
     *
     * <p>Asked at save, at preparation and at dispatch, because the image can change under a saved
     * setup between any two of them. Checking only the save let a setup saved against one image open
     * an approval, and start a build, against another that does not run it.
     */
    public Optional<String> refusal(String harness, String model, String effort) {
        var catalogue = get(harness).filter(known -> known.status() == HarnessImageResult.Status.OK);
        if (catalogue.isEmpty()) return effort == null ? Optional.empty() : Optional.of("effort_unverifiable");
        var runs = catalogue.get().find(model);
        if (runs.isEmpty()) return Optional.of("model_not_run_by_harness");
        if (effort != null && !runs.get().efforts().contains(effort)) return Optional.of("effort_not_offered");
        return Optional.empty();
    }

    /**
     * The catalogue for a harness, or empty when nothing has been heard yet.
     *
     * <p>Empty is kept distinct from a catalogue whose status says why it has no models: "we have not
     * asked yet" and "the image declares nothing" send an operator to different places.
     */
    public Optional<Catalogue> get(String harness) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT harness, image, status, models::text, observed_at FROM harness_catalogue WHERE harness=?")) {
            ps.setString(1, harness);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                // An answer for an image the configuration has since left is not served.
                if (!rs.getString("image").equals(config.agentImage().get(harness))) return Optional.empty();
                return Optional.of(new Catalogue(rs.getString("harness"), rs.getString("image"),
                        HarnessImageResult.Status.valueOf(rs.getString("status")),
                        mapper.readValue(rs.getString("models"), new TypeReference<List<HarnessImageResult.Model>>() { }),
                        rs.getTimestamp("observed_at").toInstant()));
            }
        } catch (SQLException | JsonProcessingException failure) {
            throw new IllegalStateException("The model catalogue for " + harness + " could not be read", failure);
        }
    }
}
