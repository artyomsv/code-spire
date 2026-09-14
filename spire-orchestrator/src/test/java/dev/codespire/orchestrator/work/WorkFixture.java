package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.event.*;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.provider.*;
import dev.codespire.orchestrator.repository.*;
import dev.codespire.worksource.*;
import dev.codespire.worksource.github.GitHubWorkIngress;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

/** Real registries and encrypted JDBC event store on Dev Services; every row belongs to this TEST fixture. */
abstract class WorkFixture {
    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;
    @Inject ProviderRegistry providers;
    @Inject RepositoryRegistry repositories;
    @Inject WorkSourceAdministration administration;
    @Inject WorkSourceRegistry sources;
    @Inject WorkPolicyRegistry policies;
    @Inject WorkItemIntake intake;
    @Inject WorkItemStore store;
    @Inject WorkItemResource resource;
    @Inject WorkSourceScanner scanner;
    WireMockServer forge;
    UUID account, repository, source, profile;
    final List<UUID> extraProfiles=new ArrayList<>();
    final List<String> extraItems=new ArrayList<>();
    String scope, itemId, path;
    WorkIssueLocation issue;
    static final String LABEL = "TEST-autonomous";
    static final String SECRET = "TEST-work-hook-secret";

    @BeforeEach void seedWork() {
        forge = new WireMockServer(WireMockConfiguration.options().dynamicPort()); forge.start();
        String slug = "TEST-" + UUID.randomUUID(); scope = "TEST-work/" + slug;
        account = UUID.fromString(providers.create(new ProviderInput("TEST-work-account-" + UUID.randomUUID(), "github", forge.baseUrl(),
                "bearer", null, "TEST-work-token", "TEST-bot", true, List.of(), "TEST-bot", null, "FACTORY")).id());
        repository = repositories.create(new RepositoryInput("github", forge.baseUrl(), "TEST-work", slug, true, null, account)).id();
        forge.stubFor(get(urlEqualTo("/repos/" + scope)).willReturn(okJson(mapper.createObjectNode().put("id", 10001)
                .put("full_name", scope).put("html_url", forge.baseUrl() + "/" + scope).toString())));
        source = administration.create(new WorkSourceAdministration.Input("TEST-source-" + UUID.randomUUID(), WorkSourceType.GITHUB,
                forge.baseUrl(), scope, repository, account, true)).id();
        forge.stubFor(get(urlEqualTo("/users/TEST-person")).willReturn(okJson("{\"id\":900123,\"login\":\"TEST-person\"}")));
        forge.stubFor(get(urlEqualTo("/user/900123")).willReturn(okJson("{\"id\":900123,\"login\":\"TEST-person\"}")));
        administration.saveActor(source, new WorkSourceAdministration.ActorInput("TEST-person", "900123", 1));
        profile = UUID.randomUUID();
        EnumMap<WorkPolicy.Phase,String> modes = new EnumMap<>(WorkPolicy.Phase.class);
        for (WorkPolicy.Phase phase : WorkPolicy.Phase.values()) modes.put(phase, switch (phase) {
            case DELIVER -> "pr"; case LAND -> "auto_if_green"; default -> "auto";
        });
        policies.createVersion(new WorkPolicy.Profile(profile, "TEST-profile-" + profile, 1, Math.abs(profile.hashCode() % 1000000000), modes));
        WorkPolicyRegistry.Pin pin = new WorkPolicyRegistry.Pin(profile,1);
        policies.save(repository, new WorkPolicyRegistry.Input(0,pin,Map.of(LABEL,pin)));
        issue = new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,forge.baseUrl(),"10001","50001"), "42",
                URI.create(forge.baseUrl()+"/"+scope+"/issues/42"));
        itemId = WorkItemIds.of(ScmType.GITHUB, forge.baseUrl(), sources.get(source).orElseThrow().repository(), issue.ref());
        path = "/repos/"+scope+"/issues/42";
        forge.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().toString())));
        audit("900123");
    }

    ObjectNode ticket() {
        ObjectNode ticket = mapper.createObjectNode().put("id",50001).put("number",42).put("repository_url",forge.baseUrl()+"/repos/"+scope)
                .put("html_url",issue.link().toString()).put("title","TEST-remote-title-secret").put("body","TEST-remote-body-secret")
                .put("state","open").put("updated_at","2026-09-13T12:00:00Z");
        ticket.putArray("labels").addObject().put("name",LABEL); return ticket;
    }
    void audit(String actor) {
        ObjectNode event = mapper.createObjectNode().put("id",101).put("event","labeled").put("created_at","2026-09-13T12:00:00Z");
        event.putObject("label").put("name",LABEL);
        if (actor == null) event.putNull("actor"); else event.putObject("actor").put("id",Long.parseLong(actor));
        forge.stubFor(get(urlEqualTo(path+"/timeline?per_page=100&page=1")).willReturn(okJson(mapper.createArrayNode().add(event).toString())));
    }
    WorkSourceDelivery signed(String actor) throws Exception {
        ObjectNode root = mapper.createObjectNode().put("action","labeled"); root.set("issue",ticket());
        root.putObject("repository").put("id",10001).put("full_name",scope); root.putObject("label").put("name",LABEL);
        if (actor != null) root.putObject("sender").put("id",Long.parseLong(actor));
        byte[] body = mapper.writeValueAsBytes(root);
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        List<WorkSourceSignal> signals = new GitHubWorkIngress(SECRET,mapper).translate(Map.of("X-GitHub-Event","issues",
                "X-Hub-Signature-256","sha256="+HexFormat.of().formatHex(mac.doFinal(body))),body,forge.baseUrl());
        return new WorkSourceDelivery(repository,source,UUID.randomUUID(),1,"github",forge.baseUrl(),sources.get(source).orElseThrow().repository(),
                signals.getFirst().hint().eventId(),signals.getFirst());
    }
    long count(String sql, Object value) throws SQLException {
        try (Connection c=dataSource.getConnection(); PreparedStatement ps=c.prepareStatement(sql)) {
            ps.setObject(1,value);try(ResultSet rs=ps.executeQuery()){rs.next();return rs.getLong(1);}
        }
    }
    void execute(String sql,Object value) throws SQLException {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement(sql)){ps.setObject(1,value);ps.executeUpdate();}
    }
    @AfterEach void cleanWork() throws Exception {
        try {
            Set<String> cleanup=new HashSet<>(extraItems);if(itemId!=null)cleanup.add(itemId);
            for(String owned:cleanup){
                execute("DELETE FROM work_delivery_effect WHERE work_item_id=?",owned);
                execute("DELETE FROM work_run_effect WHERE work_item_id=?",owned);
                execute("DELETE FROM llm_charge WHERE subject_kind='RUN' AND subject_id IN (SELECT run_id FROM factory_run WHERE work_item_id=?)",owned);
                execute("DELETE FROM factory_run WHERE work_item_id=?",owned);
                execute("DELETE FROM work_tracker_outbox WHERE work_item_id=?",owned);
                execute("DELETE FROM work_item_gate WHERE work_item_id=?",owned);
                execute("DELETE FROM work_phase_attempt WHERE work_item_id=?",owned);
                execute("DELETE FROM work_item_outbox WHERE work_item_id=?",owned);
                execute("DELETE FROM work_item_delivery WHERE work_item_id=?",owned);
                execute("DELETE FROM work_item WHERE id=?",owned);
                execute("DELETE FROM event_log WHERE stream_id=?",owned);
            }
            if(repository!=null){
                execute("DELETE FROM work_source_actor WHERE source_id IN (SELECT id FROM work_source WHERE repository_id=?)",repository);
                execute("DELETE FROM work_source WHERE repository_id=?",repository);
            }
            if(repository!=null){execute("DELETE FROM work_label_mapping WHERE repository_id=?",repository);execute("DELETE FROM work_repository_policy WHERE repository_id=?",repository);}
            if(profile!=null){execute("DELETE FROM autonomy_profile_version WHERE profile_id=?",profile);execute("DELETE FROM autonomy_profile WHERE id=?",profile);}
            for(UUID extra:extraProfiles){execute("DELETE FROM autonomy_profile_version WHERE profile_id=?",extra);execute("DELETE FROM autonomy_profile WHERE id=?",extra);}
            if(repository!=null){execute("DELETE FROM repository_account WHERE repository_id=?",repository);execute("DELETE FROM repository WHERE id=?",repository);}
            if(account!=null)execute("DELETE FROM scm_provider WHERE id=?",account);
        } finally { if(forge!=null)forge.stop(); }
    }
}
