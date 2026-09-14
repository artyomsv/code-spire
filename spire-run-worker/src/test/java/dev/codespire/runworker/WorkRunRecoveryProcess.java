package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.*;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.runtime.*;
import dev.codespire.runtime.docker.DockerRunRuntime;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.postgresql.ds.PGSimpleDataSource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Real worker instances in killable JVMs. Only the harness and broker are TEST boundaries. */
public final class WorkRunRecoveryProcess {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var input=mapper.readTree(System.in);String mode=input.path("mode").asText();Path directory=Path.of(args[0]);
        var command=mapper.treeToValue(input.path("command"),RunCommand.ExecuteWorkRun.class);
        String id=command.runId();if(!id.contains("TEST-"))throw new IllegalArgumentException("Only a TEST run may enter recovery proof");
        PGSimpleDataSource source=new PGSimpleDataSource();source.setUrl(input.path("url").asText());
        source.setUser(input.path("username").asText());source.setPassword(input.path("password").asText());
        var encryption=new EncryptionService(input.path("keyset").asText());
        WorkRunStore store=new WorkRunStore();store.dataSource=source;store.mapper=mapper;store.encryption=encryption;
        WorkspaceLeases leases=new WorkspaceLeases();leases.dataSource=source;store.leases=leases;
        RunClaimStore claims=new RunClaimStore();claims.dataSource=source;
        RunRegistry registry=new RunRegistry();
        Credentials credentials=new Credentials();credentials.mapper=mapper;credentials.encryption=encryption;
        var environment=new EnterpriseEnvironmentConfig(){
            @Override public EnterpriseEnvironment environment(){return EnterpriseEnvironment.NONE;}
            @Override public List<dev.codespire.secrets.SecretScrub.Credential> proxyCredentials(){return List.of();}
        };
        RunUnitBuilder builder=new RunUnitBuilder();builder.credentials=credentials;builder.enterprise=environment;
        builder.publisherImage="spire-publisher:latest";builder.maxWallClockSeconds=60;
        RunFailures failures=new RunFailures();failures.credentials=credentials;failures.enterprise=environment;
        AtomicInteger builds=new AtomicInteger();HarnessAdapter harness=WorkItemRunFixture.testHarness(builds);
        DockerRunRuntime docker=new DockerRunRuntime();
        // Restrict discovery to this exact fixture so a TEST watchdog cannot inspect unrelated units.
        PublicationRuntime runtime=(PublicationRuntime)Proxy.newProxyInstance(WorkRunRecoveryProcess.class.getClassLoader(),
                new Class<?>[]{PublicationRuntime.class},(proxy,method,arguments)->{
                    if(method.getName().equals("discoverUnits"))return docker.discoverUnits().stream().filter(unit->unit.runId().equals(id)).toList();
                    if(method.getName().equals("publishHeld") && mode.equals("claim")) {
                        write(mapper,directory,"publishing.json",Map.of("state",store.find(id).orElseThrow().state(),"builds",builds.get()));
                        new CountDownLatch(1).await(); // Parent kills this JVM after the durable permit claim, before publisher IO.
                    }
                    try{
                        Object result=method.invoke(docker,arguments);
                        if(method.getName().equals("publishHeld") && mode.equals("observe")) {
                            write(mapper,directory,"publishing.json",Map.of("state",store.find(id).orElseThrow().state(),"builds",builds.get()));
                            new CountDownLatch(1).await(); // Push returned, but terminal evidence has not reached the store.
                        }
                        return result;
                    }catch(InvocationTargetException failure){throw failure.getCause();}
                });
        List<RunResult> reports=new ArrayList<>();
        RunResultReporter reporter=new RunResultReporter(){@Override public boolean report(RunResult result){reports.add(result);return !mode.equals("build");}};
        RunLauncher launcher=new RunLauncher();launcher.runtime=runtime;launcher.builder=builder;launcher.failures=failures;
        launcher.harnesses=new HarnessRegistry(){@Override public HarnessAdapter forName(String name){return harness;}};
        WorkRunWorker worker=new WorkRunWorker();worker.store=store;worker.claims=claims;worker.leases=leases;worker.registry=registry;
        worker.launcher=launcher;worker.builder=builder;worker.runtime=runtime;worker.failures=failures;worker.results=reporter;worker.staleAfterSeconds=60;
        worker.transcript=new RunTranscript(){@Override public void emit(RunEventRecord event,java.util.function.BiConsumer<Void,Throwable> done){done.accept(null,null);}};
        if(mode.equals("build")) {
            worker.execute(Message.of((RunCommand)command),command).toCompletableFuture().join();
            var held=store.find(id).orElseThrow();
            write(mapper,directory,"ready.json",Map.of("ready",Objects.requireNonNull(held.ready()),"builds",builds.get(),"owner",leases.ownerId()));
            new CountDownLatch(1).await(); // Parent measures the held remote before killing this owner.
        } else {
            OrphanWatchdog watchdog=new OrphanWatchdog();watchdog.runtime=runtime;watchdog.leases=leases;watchdog.claims=claims;
            watchdog.registry=registry;watchdog.results=reporter;watchdog.enabled=true;watchdog.staleAfterSeconds=60;
            watchdog.sweep();
            write(mapper,directory,"recovered-"+mode+".json",Map.of("held",runtime.publicationHeld(new RunHandle(id,id)),
                    "head",store.find(id).orElseThrow().ready().head(),"builds",builds.get(),"owner",leases.ownerId()));
            if(Set.of("claim","observe").contains(mode)) {
                Path permit=directory.resolve("permit.json");long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
                while(!Files.isRegularFile(permit) && System.nanoTime()<deadline)Thread.sleep(25);
                worker.publish(mapper.readValue(Files.readAllBytes(permit),RunCommand.PublishWorkRun.class));
            } else worker.recover();
            write(mapper,directory,"published.json",Map.of("terminal",Objects.requireNonNull(store.find(id).orElseThrow().terminal()),
                    "builds",builds.get(),"reports",reports,"pending",store.pendingResults().stream().filter(result->result.runId().equals(id)).count()));
        }
        launcher.stopStreams();System.exit(0);
    }
    static void write(ObjectMapper mapper,Path directory,String name,Object value) throws Exception {
        Object encoded=value;
        if(value instanceof Map<?,?> fields) {
            var root=mapper.createObjectNode();
            for(var entry:fields.entrySet())root.set((String)entry.getKey(),entry.getValue() instanceof RunResult result
                    ?mapper.readTree(mapper.writerFor(RunResult.class).writeValueAsBytes(result)):mapper.valueToTree(entry.getValue()));
            encoded=root;
        }
        Path staged=directory.resolve(name+".tmp");Files.write(staged,mapper.writeValueAsBytes(encoded));
        Files.move(staged,directory.resolve(name),StandardCopyOption.ATOMIC_MOVE);
    }
}
