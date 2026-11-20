package dev.codespire.orchestrator.llm.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.orchestrator.llm.LlmModelRateRepository;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.llm.PricingMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** {@link ManualModels} on the {@code llm_model} and {@code llm_model_rate} tables. */
@ApplicationScoped
public class JdbcManualModels implements ManualModels {

    private static final Logger LOG = Logger.getLogger(JdbcManualModels.class);
    private static final TypeReference<Map<String, Object>> EXTRA = new TypeReference<>() { };

    @Inject
    DataSource dataSource;

    @Inject
    LlmModelRateRepository rateRepository;

    @Inject
    ObjectMapper mapper;

    @Override
    public Optional<ResolvedModel> byAnyName(List<String> names) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM llm_model WHERE name = ANY(?)")) {
            Array asked = c.createArrayOf("text", names.toArray());
            ps.setArray(1, asked);
            Map<String, ResolvedModel> found = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ResolvedModel model = toResolved(c, rs);
                    found.put(model.name(), model);
                }
            }
            return names.stream().filter(found::containsKey).findFirst().map(found::get);
        }
    }

    @Override
    public List<ResolvedModel> all() throws SQLException {
        List<ResolvedModel> models = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM llm_model ORDER BY type, name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                models.add(toResolved(c, rs));
            }
        }
        return models;
    }

    @Override
    public Set<String> allNames() throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT name FROM llm_model");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }

    private ResolvedModel toResolved(Connection c, ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        ModelParamProfile profile = new ModelParamProfile(
                LlmModelRegistry.parseTokenParam(rs.getString("output_token_param")),
                rs.getBoolean("supports_temperature"), rs.getString("reasoning_effort"),
                readExtra(rs.getString("extra_params")));
        return new ResolvedModel(rs.getString("name"), rs.getString("type"), rs.getString("label"),
                ModelSource.MANUAL, PricingMode.valueOf(rs.getString("pricing_mode")),
                rateRepository.ratesFor(c, id), rs.getBoolean("enabled"), profile, null);
    }

    private Map<String, Object> readExtra(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, EXTRA);
        } catch (JsonProcessingException e) {
            LOG.warnf("Corrupt extra_params, ignoring: %s", e.getOriginalMessage());
            return Map.of();
        }
    }
}
