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
