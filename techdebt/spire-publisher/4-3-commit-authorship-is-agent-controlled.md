# Commit authorship is the agent's to set; "authored by the machine account" is enforced by nothing

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-workspace/src/main/java/dev/codespire/workspace/WorkspaceClone.java` (writes the identity into the workspace config), `spire-publisher/.../PublishCycle.java` (gates paths, never authorship or content) |
| Found during | PR #95 four-lens review, round 2 (security-officer, findings 8 and 9) |
| Date | 2026-09-02 |

## Issue

The init step writes the machine account's name and a placeholder e-mail into the workspace's git
config, so an ordinary commit is authored by it — and `M0WalkingSkeletonTest` asserts that. An
agent can override it on any commit (`git -c user.name=x commit`, or an edited config) and the
gate judges paths only: a commit authored as a human, or one that commits the agent's own model
key into the tree, passes the same gate. The push is authenticated as the machine account either
way, which is the property that actually holds.

## Risks

- Attribution on the forge can be forged by the agent; a secret written into the tree reaches the
  remote. Both are reputational rather than access-granting today, because the branch is a
  `spire/` branch nobody merges without the reviewer.

## Suggested Solutions

- A content floor beside the path floor: the gate refuses a commit whose author differs from the
  machine account, and runs a secret scan over the added lines (the reviewer's own prompt-fence
  detectors are a start).
- Real attribution needs the account's e-mail, which M1's credential pool carries.
