---
name: docker-label-and-volume-probes
description: Measured Docker daemon facts for reviewing anything that runs operator-named images — labels store raw ESC/CR/LF, rm --force leaks anonymous volumes, volume create is idempotent on a duplicate name — with the one-line probes that proved each
metadata:
  type: reference
---

Three facts measured on the local daemon (Docker 29.6.2, 2026-09-03), each with its probe:

1. **An image label stores control bytes verbatim.** `printf 'FROM scratch\n' > Dockerfile;
   docker build -q --label "k=$(printf 'x\033[2J\r\n forged')" -t probe .` then
   `docker image inspect probe --format '{{index .Config.Labels "k"}}' | od -c` shows `033 [ 2 J \r \n`
   intact. Any tool that prints a label, ENTRYPOINT or USER to a terminal or log needs its own
   control-character neutralization — nothing in Docker does it.
2. **`removeContainerCmd(id).withForce(true)` leaks anonymous volumes** from an image `VOLUME`
   instruction unless `.withRemoveVolumes(true)` is set (`docker rm -f` without `-v`). Probe:
   `docker create --name x -v /data busybox:1.37.0 true; docker rm -f x; docker volume ls -qf dangling=true`.
3. **`createVolumeCmd().withName(existing)` is idempotent** — exit 0, the existing volume is silently
   reused, so a name collision shares state and a later `removeVolume` deletes someone else's volume.

**How to apply:** any review of `DockerImageProbe`, `DockerRunRuntime` or a test that builds images
(`ReferenceImageIT`, `TestImages`). Run the probes in the scratchpad with a unique tag/name and remove
what they create. Related: [[docker-java-wait-semantics]], [[semgrep-on-windows-git-bash]].
