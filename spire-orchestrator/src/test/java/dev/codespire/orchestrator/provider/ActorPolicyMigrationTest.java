package dev.codespire.orchestrator.provider;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production V62 against a private V61 schema, never ALTER against the live database. */
class ActorPolicyMigrationTest {
    static final PostgreSQLContainer database=new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("test_actors").withUsername("TEST-actors").withPassword("TEST-actors-only");
    static DataSource source;
    @BeforeAll static void startDatabase() {
        database.start();
        PGSimpleDataSource configured=new PGSimpleDataSource();
        configured.setURL(database.getJdbcUrl());configured.setUser(database.getUsername());configured.setPassword(database.getPassword());
        source=configured;
    }
    @AfterAll static void stopDatabase() { database.stop(); }
    String schema;
    Connection connection;
    String previous;
    UUID github=UUID.randomUUID(),bitbucket=UUID.randomUUID(),other=UUID.randomUUID();
    @BeforeEach void prepare() throws Exception {
        schema="test_actor_policy_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("61").load().migrate();
        connection=source.getConnection();previous=connection.getSchema();connection.setSchema(schema);
        UUID reviewer=account("github","REVIEWER"),factory=account("github","FACTORY"),bb=account("bitbucket-cloud","REVIEWER");
        repo(github,"github",reviewer);repo(bitbucket,"bitbucket-cloud",bb);repo(other,"bitbucket-cloud",bb);
        execute("INSERT INTO repository_account(repository_id,account_id,role) VALUES (?,?,'FACTORY')",github,factory);
        author(reviewer,"900123");author(reviewer,"TEST-unresolved");author(factory,"900999");
        author(bb,"TEST-observed-id");author(bb,"TEST-unresolved");author(bb,"900777");
        execute("INSERT INTO review_status(review_id,workspace,slug,pr_id,status,author_id,repository_id) VALUES ('TEST-review','TEST-namespace','TEST-repo',1,'completed','TEST-observed-id',?)",bitbucket);
    }
    void migrate() {Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();}
    UUID account(String type,String role) throws Exception {
        UUID id=UUID.randomUUID();
        execute("INSERT INTO scm_provider(id,name,type,base_url,auth_kind,auth_secret,role) VALUES (?,'TEST-account',?,'https://TEST-forge.example.test','bearer','TEST-ciphertext',?)",id,type,role);
        return id;
    }
    void repo(UUID id,String type,UUID account) throws Exception {
        execute("INSERT INTO repository(id,scm_type,forge_origin,workspace,slug) VALUES (?,?,'https://TEST-forge.example.test','TEST-namespace',?)",id,type,"TEST-"+id);
        execute("INSERT INTO repository_account(repository_id,account_id,role) VALUES (?,?,'REVIEWER')",id,account);
    }
    void author(UUID account,String author) throws Exception {execute("INSERT INTO provider_author(provider_id,author) VALUES (?,?)",account,author);}
    @Test void onlyEvidencedStableReviewerIdsBecomeRepositoryGrants() throws Exception {
        migrate();
        Set<String> actual=new HashSet<>();
        try(var ps=connection.prepareStatement("SELECT repository_id,actor_id,effect FROM repository_fix_actor");var rs=ps.executeQuery()) {
            while(rs.next())actual.add(rs.getString(1)+"/"+rs.getString(2)+"/"+rs.getString(3));
        }
        assertEquals(Set.of(github+"/900123/ALLOW",bitbucket+"/TEST-observed-id/ALLOW"),actual);
        try(var ps=connection.prepareStatement("SELECT count(*) FROM provider_author");var rs=ps.executeQuery()) {assertTrue(rs.next());assertEquals(6,rs.getInt(1));}
    }
    @Test void aSecondEffectForTheSamePersonViolatesTheProductionConstraint() throws Exception {
        migrate();
        SQLException failure=assertThrows(SQLException.class,()->execute("INSERT INTO repository_fix_actor(repository_id,actor_id,effect) VALUES (?,'900123','DENY')",github));
        assertEquals("23505",failure.getSQLState());
    }
    @Test void unknownEffectsCannotBeStored() throws Exception {
        migrate();
        SQLException failure=assertThrows(SQLException.class,()->execute("INSERT INTO repository_fix_actor(repository_id,actor_id,effect) VALUES (?,'TEST-new','OTHER')",github));
        assertEquals("23514",failure.getSQLState());
    }
    @Test void emptyIdsCannotBeStored() throws Exception {
        migrate();
        SQLException failure=assertThrows(SQLException.class,()->execute("INSERT INTO repository_fix_actor(repository_id,actor_id,effect) VALUES (?,'','ALLOW')",github));
        assertEquals("23514",failure.getSQLState());
    }
    void execute(String sql,Object...parameters) throws Exception {
        try(var ps=connection.prepareStatement(sql)){for(int i=0;i<parameters.length;i++)ps.setObject(i+1,parameters[i]);ps.executeUpdate();}
    }
    @AfterEach void cleanup() throws Exception {
        if(connection!=null){connection.setSchema(previous);connection.close();}
        try(var c=source.getConnection();var statement=c.createStatement()){statement.execute("DROP SCHEMA "+schema+" CASCADE");}
    }
}
