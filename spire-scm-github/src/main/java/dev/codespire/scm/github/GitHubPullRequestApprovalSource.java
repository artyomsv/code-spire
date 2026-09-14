package dev.codespire.scm.github;

import dev.codespire.contract.port.PullRequestApprovalSource;
import dev.codespire.contract.scm.RepoRef;

/** Re-reads the named native review; webhook state is only a wake-up signal. */
public final class GitHubPullRequestApprovalSource implements PullRequestApprovalSource {
    private final GitHubClient client;
    public GitHubPullRequestApprovalSource(GitHubClient client) { this.client=client; }
    @Override public boolean available() { return true; }
    @Override public Approval read(RepoRef repository,long pullRequest,String reviewId) {
        if(pullRequest<1 || reviewId==null || !reviewId.matches("[1-9][0-9]*"))
            throw new IllegalArgumentException("A native review identity is required");
        String path="/repos/"+repository.full()+"/pulls/"+pullRequest;
        var review=client.getIdentityJson(path+"/reviews/"+reviewId);
        String actor=review.path("user").path("id").asText("");
        if(!reviewId.equals(review.path("id").asText()) || !actor.matches("[1-9][0-9]*"))
            throw new IllegalStateException("Review identity could not be verified");
        // A named approval may have been superseded by this person's later changes request.
        boolean complete=false,latestApproved=false;
        String latest=null;
        for(int page=1;page<=20;page++) {
            var rows=client.getIdentityJson(path+"/reviews?per_page=100&page="+page);
            if(!rows.isArray())throw new IllegalStateException("Review history could not be verified");
            for(var row:rows)if(actor.equals(row.path("user").path("id").asText())
                    && java.util.Set.of("APPROVED","CHANGES_REQUESTED","DISMISSED").contains(row.path("state").asText())) {
                latest=row.path("id").asText();latestApproved="APPROVED".equals(row.path("state").asText());
            }
            if(rows.size()<100){complete=true;break;}
        }
        boolean current=complete && latestApproved && reviewId.equals(latest);
        var pull=client.getIdentityJson(path);
        boolean open="open".equals(pull.path("state").asText()) && !pull.path("merged").asBoolean(true)
                && pull.path("number").asLong()==pullRequest;
        return new Approval(actor,review.path("commit_id").asText(""),
                current && open && "APPROVED".equals(review.path("state").asText()),
                "User".equals(review.path("user").path("type").asText()));
    }
}
