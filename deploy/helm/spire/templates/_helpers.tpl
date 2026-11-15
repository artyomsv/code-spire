{{/*
Shared helpers. The `required` calls here are the chart's fail-fast contract: a missing secret name or
trusted-proxy CIDR stops the render with a message naming what to set, rather than installing
something that starts and then misbehaves.
*/}}

{{- define "spire.labels" -}}
app.kubernetes.io/name: spire
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end -}}

{{- define "spire.secretName" -}}
{{ required "secrets.existingSecret is required. Create a Secret holding POSTGRES_PASSWORD, SPIRE_ENCRYPTION_KEYSET, SPIRE_OIDC_ORCHESTRATOR_SECRET, SPIRE_OIDC_GATEWAY_SECRET and SPIRE_OIDC_WORKER_SECRET, then set secrets.existingSecret to its name. This chart never generates one: the keyset decrypts data already at rest, so a generated value would rotate on upgrade and make every encrypted row unreadable." .Values.secrets.existingSecret }}
{{- end -}}

{{- define "spire.gatewaySecretName" -}}
{{ required "secrets.gatewayExistingSecret is required. Create a SEPARATE Secret holding GATEWAY_POSTGRES_PASSWORD and SPIRE_ENCRYPTION_WEBHOOK_KEYSET. It must not be the same Secret as secrets.existingSecret: the gateway mounts only this one, so a compromised edge can decrypt webhook secrets and nothing else." .Values.secrets.gatewayExistingSecret }}
{{- end -}}

{{- define "spire.trustedProxies" -}}
{{ required "trustedProxies is required: the network the dashboard pod runs on (e.g. the cluster pod CIDR). The services believe X-Forwarded-For and -Proto in prod and refuse to start unless told who may be believed." .Values.trustedProxies }}
{{- end -}}

{{- define "spire.postgresHost" -}}
{{- if .Values.postgres.bundled -}}
spire-postgres
{{- else -}}
{{ required "postgres.host is required when postgres.bundled is false." .Values.postgres.host }}
{{- end -}}
{{- end -}}

{{- define "spire.kafkaBootstrap" -}}
{{- if .Values.kafka.bundled -}}
spire-redpanda:9092
{{- else -}}
{{ required "kafka.bootstrapServers is required when kafka.bundled is false." .Values.kafka.bootstrapServers }}
{{- end -}}
{{- end -}}

{{- define "spire.authServerUrl" -}}
{{ required "oidc.authServerUrl is required. It must be reachable from the pods AND be the issuer the browser sees — see deploy/README.md for the realm contract." .Values.oidc.authServerUrl }}
{{- end -}}

{{/*
Environment every service needs. The datasource URL and Kafka bootstrap are here because all three
services resolve them only under their %dev profile: a packaged run that is not told them fails Flyway
at boot and silently falls back to localhost for the broker.
*/}}
{{- define "spire.commonEnv" -}}
- name: QUARKUS_PROFILE
  value: prod
- name: QUARKUS_DATASOURCE_JDBC_URL
  value: jdbc:postgresql://{{ include "spire.postgresHost" . }}:{{ .Values.postgres.port }}/{{ .Values.postgres.database }}
- name: KAFKA_BOOTSTRAP_SERVERS
  value: {{ include "spire.kafkaBootstrap" . | quote }}
- name: SPIRE_OIDC_AUTH_SERVER_URL
  value: {{ include "spire.authServerUrl" . | quote }}
- name: SPIRE_TRUSTED_PROXIES
  value: {{ include "spire.trustedProxies" . | quote }}
{{- end -}}

{{/*
One service image. An empty image.tag means the chart's appVersion, so a chart packaged at a release
(release.yml sets --app-version) pulls that release's images rather than a moving tag.
*/}}
{{- define "spire.image" -}}
"{{ .root.Values.image.registry }}/{{ .name }}:{{ .root.Values.image.tag | default .root.Chart.AppVersion }}"
{{- end -}}

{{/*
A password reference: the chart's own Secret and key, or the Secret and key the operator names (a
CloudNativePG basic-auth Secret's `password`, so a password lives in one place). Half a reference is
refused: a name without a key, or a key without a name, would silently read the wrong place.

  include "spire.passwordRef" (dict "value" "postgres.passwordSecret" "ref" .Values.postgres.passwordSecret
                                    "secret" (include "spire.secretName" .) "key" "POSTGRES_PASSWORD")
*/}}
{{- define "spire.passwordRef" -}}
{{- $ref := .ref | default dict -}}
{{- $name := $ref.name | default "" -}}
{{- $key := $ref.key | default "" -}}
{{- if ne (empty $name) (empty $key) -}}
{{- fail (printf "%s needs both name and key, or neither. Got name=%q key=%q." .value $name $key) -}}
{{- end -}}
secretKeyRef:
  name: {{ $name | default .secret }}
  key: {{ $key | default .key }}
{{- end -}}

{{- define "spire.dbPasswordRef" -}}
{{ include "spire.passwordRef" (dict "value" "postgres.passwordSecret" "ref" .Values.postgres.passwordSecret "secret" (include "spire.secretName" .) "key" "POSTGRES_PASSWORD") }}
{{- end -}}

{{/*
The gateway's database password. Its Secret may never be the main one: the main Secret holds the
master keyset, and the gateway is the internet-facing edge (ADR-015).
*/}}
{{- define "spire.gatewayDbPasswordRef" -}}
{{- $ref := .Values.postgres.gatewayPasswordSecret | default dict -}}
{{- if and $ref.name (eq $ref.name .Values.secrets.existingSecret) -}}
{{- fail (printf "postgres.gatewayPasswordSecret.name is %q, the main Secret (secrets.existingSecret). That Secret holds the master keyset, and the gateway must never reference it. Name the Secret that holds only the gateway role's password." $ref.name) -}}
{{- end -}}
{{- $owner := dig "passwordSecret" "name" "" .Values.postgres -}}
{{- if and $ref.name $owner (eq $ref.name $owner) -}}
{{- fail (printf "postgres.gatewayPasswordSecret.name is %q, the database owner's Secret (postgres.passwordSecret). The gateway must never hold the owner's password (ADR-015). Name the Secret that holds only the gateway role's password." $ref.name) -}}
{{- end -}}
{{ include "spire.passwordRef" (dict "value" "postgres.gatewayPasswordSecret" "ref" $ref "secret" (include "spire.gatewaySecretName" .) "key" "GATEWAY_POSTGRES_PASSWORD") }}
{{- end -}}

{{/*
A container's env: the chart's own entries, then <service>.extraEnv. The chart's names are read from
the rendered list itself, not from a second list kept by hand, so a new chart entry is protected the
moment it exists. A repeated name is refused: the later entry would override a chart-owned setting
(the gateway's database user, for one) and server-side apply rejects the object anyway.

  include "spire.env" (dict "root" . "service" "orchestrator" "own" (include "spire.orchestrator.env" .))
*/}}
{{- define "spire.env" -}}
{{- $svc := index .root.Values .service | default dict -}}
{{- $names := list -}}
{{- range (fromYamlArray .own) -}}
{{- if not (and (kindIs "map" .) (hasKey . "name")) -}}
{{- fail (printf "internal: the chart's own env could not be read as a list of named entries: %v" .) -}}
{{- end -}}
{{- $names = append $names .name -}}
{{- end -}}
{{- range $i, $e := $svc.extraEnv -}}
{{- if has $e.name $names -}}
{{- fail (printf "%s.extraEnv[%d] sets %s, which this container already has. extraEnv may only add names; it cannot override a setting the chart owns." $.service $i $e.name) -}}
{{- end -}}
{{- $names = append $names $e.name -}}
{{- end -}}
{{ .own }}
{{- with $svc.extraEnv }}
{{ toYaml . }}
{{- end -}}
{{- end -}}

{{/*
The refusals that keep the master keyset off the gateway when extraEnv and extraEnvFrom are used.
render.sh greps env NAMES, so it cannot see an envFrom; these refusals are the guard. A different
Secret that also holds the master keyset cannot be policed here, and deploy/README.md says so.
*/}}
{{- define "spire.gatewayExtraGuards" -}}
{{- $main := .Values.secrets.existingSecret -}}
{{- $owner := dig "passwordSecret" "name" "" .Values.postgres -}}
{{- range $i, $e := .Values.gateway.extraEnv -}}
{{- if eq $e.name "SPIRE_ENCRYPTION_KEYSET" -}}
{{- fail (printf "gateway.extraEnv[%d] sets SPIRE_ENCRYPTION_KEYSET. The gateway is the internet-facing edge and must never hold the master keyset (ADR-015)." $i) -}}
{{- end -}}
{{- $ref := dig "valueFrom" "secretKeyRef" "name" "" $e -}}
{{- if and $main (eq $ref $main) -}}
{{- fail (printf "gateway.extraEnv[%d] reads from %q, the main Secret (secrets.existingSecret), which holds the master keyset. The gateway must never reference it." $i $main) -}}
{{- end -}}
{{- if and $owner (eq $ref $owner) -}}
{{- fail (printf "gateway.extraEnv[%d] reads from %q, the database owner's Secret (postgres.passwordSecret). The gateway must never hold the owner's password (ADR-015)." $i $owner) -}}
{{- end -}}
{{- end -}}
{{- range $i, $e := .Values.gateway.extraEnvFrom -}}
{{- $ref := dig "secretRef" "name" "" $e -}}
{{- if and $main (eq $ref $main) -}}
{{- fail (printf "gateway.extraEnvFrom[%d] loads %q, the main Secret (secrets.existingSecret), which holds the master keyset. The gateway must never reference it." $i $main) -}}
{{- end -}}
{{- if and $owner (eq $ref $owner) -}}
{{- fail (printf "gateway.extraEnvFrom[%d] loads %q, the database owner's Secret (postgres.passwordSecret). The gateway must never hold the owner's password (ADR-015)." $i $owner) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "spire.probes" -}}
livenessProbe:
  httpGet:
    path: /q/health/live
    port: http
  initialDelaySeconds: 30
  periodSeconds: 15
readinessProbe:
  httpGet:
    path: /q/health/ready
    port: http
  initialDelaySeconds: 15
  periodSeconds: 10
  failureThreshold: 12
{{- end -}}

{{- define "spire.securityContext" -}}
securityContext:
  runAsNonRoot: true
  runAsUser: 1001
  allowPrivilegeEscalation: false
  capabilities:
    drop: ["ALL"]
{{- end -}}
