package dev.codespire.orchestrator.work;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import java.io.PrintWriter;

/** The worker's one Gradle Test task owns this separate TEST JVM and its service lifetime. */
public final class WorkItemRunJourneyProcess {
    public static void main(String[] args) {
        System.setProperty("spire.test.journey.directory",args[0]);
        var listener=new SummaryGeneratingListener();
        try(var session=LauncherFactory.openSession()) {
            // Opening the session installs Quarkus's test classloader. Do not load the test
            // via a class literal before that listener has had a chance to replace it.
            var request=LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass("dev.codespire.orchestrator.work.WorkItemRunJourneyDriver")).build();
            session.getLauncher().execute(request,listener);
        }
        var result=listener.getSummary();result.printTo(new PrintWriter(System.out,true));result.printFailuresTo(new PrintWriter(System.out,true));
        // The session is closed; exit also runs the child-owned Dev Services shutdown hooks.
        System.exit(result.getTestsSucceededCount()==1 && result.getTotalFailureCount()==0?0:1);
    }
}
