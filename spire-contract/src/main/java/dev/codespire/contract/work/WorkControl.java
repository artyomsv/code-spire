package dev.codespire.contract.work;

/** Recorded serving identities belong to the build, even after account rotation or a rename. */
public record WorkControl(String runId,WorkRunBinding build,String branch,String factoryActor,String reviewerActor,
                          String operator,String note,String observedHead,String trackerActor) {
    public WorkControl(String runId,WorkRunBinding build,String branch,String factoryActor,String reviewerActor,String operator,String note,String observedHead) {
        this(runId,build,branch,factoryActor,reviewerActor,operator,note,observedHead,null);
    }
    public WorkControl action(String subject,String decisionNote,String head) {
        return new WorkControl(runId,build,branch,factoryActor,reviewerActor,subject,decisionNote,head,trackerActor);
    }
    public boolean machine(String actor) {
        return actor!=null && !actor.isBlank() && (actor.equals(factoryActor) || actor.equals(reviewerActor));
    }
    public boolean trackerMachine(String actor) {return actor!=null && !actor.isBlank() && actor.equals(trackerActor);}
}
