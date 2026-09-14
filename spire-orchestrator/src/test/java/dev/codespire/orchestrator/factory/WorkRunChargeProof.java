package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.attention.AttentionBroadcaster;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * TEST-only process bridge: feed the real worker's two wire results through production RunCharges
 * and ReviewProjection into a fresh schema built by every production migration. Only the model
 * catalog's rate and UI notifications are fixtures. The worker gains no dependency on a deployable.
 */
public final class WorkRunChargeProof {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var input=mapper.readTree(System.in);
        RunResult ready=mapper.treeToValue(input.path("ready"),RunResult.class);
        RunResult finished=mapper.treeToValue(input.path("finished"),RunResult.class);
        if(!ready.runId().contains("TEST-") || !ready.runId().equals(finished.runId()))throw new IllegalArgumentException("Only one TEST run may enter this proof");
        PGSimpleDataSource source=new PGSimpleDataSource();source.setUrl(input.path("url").asText());
        source.setUser(input.path("username").asText());source.setPassword(input.path("password").asText());
        String schema="test_m3_charge_"+UUID.randomUUID().toString().replace("-","");
        source.setCurrentSchema(schema);
        Flyway migrations=Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
                .locations("filesystem:"+Path.of(input.path("root").asText(),"spire-orchestrator/src/main/resources/db/migration"))
                .cleanDisabled(false).load();
        try {
            migrations.migrate();
            ReviewProjection ledger=new ReviewProjection();set(ledger,"dataSource",source);
            set(ledger,"attention",new AttentionBroadcaster(){@Override public void refresh(){ /* TEST: no dashboard websocket */ }});
            RunCharges charges=new RunCharges();charges.ledger=ledger;
            charges.runs=new FactoryRunProjection(){
                @Override public Optional<String> modelOf(String id){return Optional.of("TEST-metered-model");}
                @Override public Optional<UUID> harnessCredentialOf(String id){return Optional.empty();}
                @Override protected void push(String id){ /* TEST: no dashboard websocket */ }
            };
            charges.pricer=new LlmModelPricer(){@Override public List<ChargeLine> priceCall(String model,ModelUsage usage){
                // A fixed TEST rate; token decoding, call identity, rounding, SQL and dedup are production.
                if(!usage.reconciled())return List.of(ChargeLine.unknown(TokenType.TOTAL,0));
                return usage.counts().stream().map(count->ChargeLine.metered(count.type(),count.tokens(),1_000_000)).toList();
            }};
            charges.record(ready);
            Map<String,Object> first=measure(source,ready.runId());
            charges.record(finished);charges.record(ready);charges.record(finished);
            Map<String,Object> last=measure(source,ready.runId());
            System.out.println("TEST_CHARGE_PROOF "+mapper.writeValueAsString(Map.of("afterReady",first,"afterRedelivery",last)));
        } finally {
            try(Connection c=source.getConnection();PreparedStatement ps=c.prepareStatement("DELETE FROM llm_charge WHERE subject_id=?")) {
                ps.setString(1,ready.runId());ps.executeUpdate();
            } finally {
                // Generated alphanumeric schema on the parent's isolated Testcontainers database only.
                migrations.clean();
            }
        }
    }

    private static Map<String,Object> measure(PGSimpleDataSource source,String id) throws SQLException {
        try(Connection c=source.getConnection();PreparedStatement ps=c.prepareStatement("""
                SELECT count(*) AS rows,count(DISTINCT call_ref) AS calls,sum(tokens) AS tokens,
                    sum(cost_millicents) AS cost,count(*) FILTER (WHERE pricing_mode='UNKNOWN') AS unknown
                FROM llm_charge WHERE subject_id=? AND subject_kind='RUN' AND capability='BUILD'
                """)) {
            ps.setString(1,id);try(ResultSet rs=ps.executeQuery()){rs.next();Map<String,Object> values=new LinkedHashMap<>();
                for(String key:List.of("rows","calls","tokens","cost","unknown"))values.put(key,rs.getObject(key));return values;}
        }
    }
    private static void set(Object target,String name,Object value) throws ReflectiveOperationException {
        var field=ReviewProjection.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}
