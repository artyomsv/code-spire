package dev.codespire.worksource;

import java.util.UUID;
import java.util.regex.Pattern;

/** Authenticated comment coordinates and an optional exact command; no tracker prose on the bus. */
public record WorkSourceActivity(String id,String actorId,GateAnswer answer,java.time.Instant occurredAt,boolean retired) {
    public WorkSourceActivity(String id,String actorId,GateAnswer answer) {this(id,actorId,answer,null,false);}
    public WorkSourceActivity at(java.time.Instant time) {return new WorkSourceActivity(id,actorId,answer,time,retired);}
    private static final Pattern COMMAND=Pattern.compile("^/(approve|reject) ([0-9a-fA-F-]{36}) ([1-9][0-9]*) ([0-9a-f]{40}|[0-9a-f]{64}|-)$");
    public record GateAnswer(UUID gateId,long generation,String artifact,boolean approve) {}
    public static WorkSourceActivity comment(String id,String actor,String text) {
        GateAnswer answer=null;
        var match=COMMAND.matcher(text==null?"":text.strip());
        if(match.matches())try {
            answer=new GateAnswer(UUID.fromString(match.group(2)),Long.parseLong(match.group(3)),
                    "-".equals(match.group(4))?null:match.group(4),"approve".equals(match.group(1)));
        }catch(IllegalArgumentException malformed){ /* Not a workflow command. */ }
        return new WorkSourceActivity(id,actor,answer);
    }
}
