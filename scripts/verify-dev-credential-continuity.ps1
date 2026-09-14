param(
    [Parameter(Mandatory)][ValidateSet('Capture', 'Compare')][string]$Mode,
    [Parameter(Mandatory)][string]$Snapshot,
    [ValidateSet('Credentials', 'Webhooks')][string]$Scope = 'Credentials'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradleRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$cacheRoot = Join-Path $gradleRoot 'caches/modules-2/files-2.1'
$encryptionClasses = Join-Path $repoRoot 'spire-encryption/build/classes/java/main'
if (-not (Test-Path "$encryptionClasses/dev/codespire/encryption/EncryptionService.class")) {
    throw 'Build :spire-encryption:classes first, using JDK 25.'
}
$classpath = @($encryptionClasses)
foreach ($artifact in @('com.google.crypto.tink/tink/1.23.0', 'com.google.protobuf/protobuf-java', 'com.google.code.gson/gson', 'org.postgresql/postgresql')) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $artifact) -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw "Missing cached runtime dependency: $artifact. Build the orchestrator first." }
    $classpath += $jar.FullName
}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$process = [Diagnostics.Process]::new()
$process.StartInfo = [Diagnostics.ProcessStartInfo]::new($java)
$process.StartInfo.UseShellExecute = $false
# Set only the child process environment. Never echo .env, secrets or the resulting command environment.
foreach ($line in [IO.File]::ReadAllLines((Join-Path $repoRoot '.env'))) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $name = $Matches[1]; $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $process.StartInfo.Environment[$name] = $value
    }
}
@('--class-path', ($classpath -join ';'), (Join-Path $PSScriptRoot 'DevCredentialContinuity.java'), $Mode, [IO.Path]::GetFullPath($Snapshot), $Scope) |
    ForEach-Object { $process.StartInfo.ArgumentList.Add($_) }
try {
    [void]$process.Start(); $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'Credential continuity proof failed.' }
} finally { $process.Dispose() }
