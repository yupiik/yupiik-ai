#!/usr/bin/env bash
# Offline by default. Operator-only disposable infrastructure canary, NOT an app test.
set -euo pipefail
umask 077

usage() {
  cat <<'USAGE'
Usage: check-context-docker.sh [--check]
       check-context-docker.sh --execute --image SHA256_ID_OR_REPOSITORY_DIGEST \
         --evidence-root /private/persistent/evidence [--attempt-root /run/yca-slack-context-attempts] \
         [--scan-root /private/persistent/state ...] [--allow-swap-backed-tmpfs]
Default/--check: local fixture self-tests only; no Docker, mount or network calls.
--execute: use an already-loaded, vetted image with /bin/sh, cat, mkdir, cp, sleep,
          stat and grep; local /var/run/docker.sock only; never pull or build.
Provision the dedicated noswap tmpfs separately. No production credentials needed.
--allow-swap-backed-tmpfs: execute-only operator acceptance of missing noswap;
          tmpfs, nodev, nosuid, noexec, private ownership and logging remain required.
          Swap can retain confidential content; this is NOT noswap qualification.
          Application environment flags never enable this script exception.
Evidence is nonsecret runtime identity/status only. Scan roots are read, never changed.
USAGE
}
fail() { printf '%s\n' "$1" >&2; exit 1; }
require_options() {
  local options=",$1," option
  for option in noswap nodev nosuid noexec; do
    if [[ "$option" == noswap && "$allow_swap_backed_tmpfs" == true ]]; then
      continue
    fi
    case "$options" in
      *",$option,"*) ;;
      *) return 1 ;;
    esac
  done
}
check_mountinfo() {
  local path=$1 file=$2 line options
  line=$(awk -v p="$path" '$5 == p { print; count++ } END { if (count != 1) exit 1 }' "$file") || return 1
  [[ "$line" == *' - tmpfs '* ]] || return 1
  options=$(printf '%s\n' "$line" | awk '{for (i=1; i<=NF; i++) {if ($i == "-") {print $6 "," $(i+3); exit}}}')
  require_options "$options"
}
normalize_mounts() {
  # Docker appends a record newline after the template's final println.
  # Ignore empty formatting lines, never nonempty mount records.
  LC_ALL=C sort | sed '/^$/d'
}
offline_check() {
  # Both policies are exercised locally regardless of ambient application settings.
  allow_swap_backed_tmpfs=false
  grep -qx 'SLACK_CONTEXT_ALLOWSWAPBACKEDTMPFS=false' "$(dirname -- "${BASH_SOURCE[0]}")/controller-context.env.example" || fail 'Overlay must default swap-backed tmpfs acceptance to false'
  require_options 'rw,noswap,nodev,nosuid,noexec,size=524288k'
  local missing
  for missing in 'rw,nodev,nosuid,noexec' 'rw,noswap,nosuid,noexec' 'rw,noswap,nodev,noexec' 'rw,noswap,nodev,nosuid'; do
    if require_options "$missing"; then
      fail 'Offline option rejection failed'
    fi
  done
  # Process substitution only: no filesystem or daemon operations.
  check_mountinfo /attempt <(printf '%s\n' '42 1 0:40 /probe /attempt rw,nosuid,nodev,noexec - tmpfs tmpfs rw,noswap,size=524288k')
  if check_mountinfo /attempt <(printf '%s\n' '42 1 8:1 /probe /attempt rw,nosuid,nodev,noexec - ext4 /dev/test rw'); then
    fail 'Offline filesystem rejection failed'
  fi
  if check_mountinfo /attempt <(printf '%s\n' '42 1 0:40 /probe /attempt rw,nosuid,nodev,noexec - tmpfs tmpfs rw,size=524288k'); then
    fail 'Offline swap-capable mount rejection failed'
  fi
  allow_swap_backed_tmpfs=true
  require_options 'rw,nodev,nosuid,noexec'
  check_mountinfo /attempt <(printf '%s\n' '42 1 0:40 /probe /attempt rw,nosuid,nodev,noexec - tmpfs tmpfs rw,size=524288k')
  for missing in 'rw,nosuid,noexec' 'rw,nodev,noexec' 'rw,nodev,nosuid'; do
    if require_options "$missing"; then
      fail 'Offline opt-in required-option rejection failed'
    fi
    if check_mountinfo /attempt <(printf '42 1 0:40 /probe /attempt %s - tmpfs tmpfs rw\n' "$missing"); then
      fail 'Offline opt-in mount-option rejection failed'
    fi
  done
  if check_mountinfo /attempt <(printf '%s\n' '42 1 8:1 /probe /attempt rw,nosuid,nodev,noexec - ext4 /dev/test rw'); then
    fail 'Offline opt-in filesystem rejection failed'
  fi
  allow_swap_backed_tmpfs=false
  [[ $(printf '\n/source|/attempt|bind\n\n' | normalize_mounts) == '/source|/attempt|bind' ]] || fail 'Mount formatting normalization failed'
  [[ $(printf '/other|/extra|volume\n/source|/attempt|bind\n\n' | normalize_mounts) != '/source|/attempt|bind' ]] || fail 'Extra mount normalization rejection failed'
  printf '%s\n' 'PASS: offline option and mountinfo fixtures only; Docker acceptance NOT executed.'
}

mode=check
allow_swap_backed_tmpfs=false
image=
attempt_root=/run/yca-slack-context-attempts
evidence_root=
scan_roots=()
while (($#)); do
  case "$1" in
    --check) mode=check; shift ;;
    --execute) mode=execute; shift ;;
    --allow-swap-backed-tmpfs) allow_swap_backed_tmpfs=true; shift ;;
    --image|--attempt-root|--evidence-root|--scan-root)
      (($# >= 2)) || fail 'Missing option value'
      case "$1" in
        --image) image=$2 ;;
        --attempt-root) attempt_root=$2 ;;
        --evidence-root) evidence_root=$2 ;;
        --scan-root) scan_roots+=("$2") ;;
      esac
      shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; fail 'Unknown option' ;;
  esac
done
if [[ "$mode" == check ]]; then
  [[ "$allow_swap_backed_tmpfs" == false ]] || fail '--allow-swap-backed-tmpfs requires --execute'
  offline_check
  exit 0
fi
if [[ "$allow_swap_backed_tmpfs" == true ]]; then
  printf '%s\n' 'WARNING: operator accepted swap-backed tmpfs; confidential content may persist in swap after cleanup. No zero-retention guarantee; NOT noswap qualification.' >&2
fi
[[ $(uname -s) == Linux ]] || fail 'Linux Docker only'
[[ "$image" =~ ^([^[:space:]]+@)?sha256:[a-f0-9]{64}$ ]] || fail 'Supply an immutable local image ID or repository digest'
for tool in docker findmnt realpath stat mktemp grep awk sort sed; do
  command -v "$tool" >/dev/null || fail 'Missing required host tool'
done
# Restrict path syntax so Docker --mount and mountinfo parsing cannot reinterpret it.
validate_path() {
  [[ "$1" =~ ^/[a-zA-Z0-9_./-]+$ && "$1" != / && -d "$1" ]] || fail 'Use an existing absolute simple directory path'
  [[ $(realpath -e -- "$1") == "$1" ]] || fail 'Symlinks or noncanonical directory paths are prohibited'
}
validate_path "$attempt_root"
validate_path "$evidence_root"
[[ $(stat -c '%u:%a' "$evidence_root") == "$(id -u):700" ]] || fail 'Evidence root must be caller-owned mode 0700'
[[ $(findmnt -n -T "$attempt_root" -o TARGET) == "$attempt_root" ]] || fail 'Attempt root must be a dedicated mount'
[[ $(findmnt -n -T "$attempt_root" -o FSTYPE) == tmpfs ]] || fail 'Attempt root must be tmpfs'
require_options "$(findmnt -n -T "$attempt_root" -o OPTIONS)" || fail 'Required tmpfs flags missing (noswap required unless explicitly accepted)'
[[ $(stat -c '%u:%g:%a' "$attempt_root") == 10002:10002:700 ]] || fail 'Attempt root must be 10002:10002 mode 0700'
case $(findmnt -n -T "$evidence_root" -o FSTYPE) in
  tmpfs|ramfs) fail 'Runtime evidence must survive tmpfs loss' ;;
esac
for path in "$evidence_root" "${scan_roots[@]}"; do
  validate_path "$path"
  [[ "$path" != "$attempt_root" && "$path" != "$attempt_root/"* && "$attempt_root" != "$path/"* ]] || fail 'Persistent scan/evidence and attempt roots must be disjoint'
done
# Explicit local socket; ignore ambient Docker contexts/remote endpoints.
docker_local() { command docker --host unix:///var/run/docker.sock "$@"; }
attempt=$(mktemp -d "$attempt_root/canary.XXXXXXXX")
evidence=$(mktemp -d "$evidence_root/context-canary.XXXXXXXX")
name="yca-context-canary-${attempt##*.}"
container=
cleaned=false
printf 'name=%s\nattempt=%s\nstatus=launch-intent\n' "$name" "$attempt" > "$evidence/runtime.txt"
printf 'Runtime evidence: %s\n' "$evidence"
cleanup() {
  local rc=$? identity running owner status
  trap - EXIT INT TERM
  # An inspect failure is NOT proof of termination, even if creation failed.
  if identity=$(docker_local container inspect --format '{{.Id}}|{{index .Config.Labels "io.yupiik.yca.context-canary"}}|{{.State.Running}}|{{.State.Status}}' "$name" 2>"$attempt/cleanup.err"); then
    IFS='|' read -r container owner running status <<< "$identity"
    if [[ "$container" =~ ^[a-f0-9]{64}$ && "$owner" == "$name" ]]; then
      printf 'id=%s\n' "$container" >> "$evidence/runtime.txt"
      if [[ "$running" == true ]]; then
        docker_local stop --time -1 "$container" >"$attempt/stop.out" 2>"$attempt/stop.err" || true
      fi
      if status=$(docker_local container inspect --format '{{.State.Status}}' "$container" 2>"$attempt/confirm.err") && [[ "$status" == exited ]]; then
        if docker_local rm "$container" >"$attempt/remove.out" 2>"$attempt/remove.err"; then
          cleaned=true
        fi
      fi
    fi
  fi
  if [[ "$cleaned" == true ]]; then
    rm -rf -- "$attempt"
    printf 'status=termination-confirmed-and-removed\n' >> "$evidence/runtime.txt"
    if [[ "$rc" == 0 ]]; then
      printf '%s\n' 'PASS: disposable infrastructure canary checks and confirmed cleanup; NOT assembled Slack/tool/model/publication acceptance.'
      if [[ "$allow_swap_backed_tmpfs" == true ]]; then
        printf '%s\n' 'Swap-backed tmpfs exception accepted: PASS is NOT noswap qualification or a zero-retention guarantee.'
      else
        printf '%s\n' 'Strict noswap mount checks passed; this is not a host-wide zero-retention guarantee.'
      fi
    fi
  else
    printf 'status=unconfirmed-retain-and-reconcile\n' >> "$evidence/runtime.txt"
    printf '%s\n' 'Termination/removal unconfirmed: retain runtime evidence and attempt; reconcile manually. No admission ledger was modified.' >&2
    rc=1
  fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Root operators or UID 10002 can provision the disposable directory.
if [[ $(id -u) == 0 ]]; then
  chown 10002:10002 "$attempt"
fi
[[ $(stat -c '%u:%g:%a' "$attempt") == 10002:10002:700 ]] || fail 'Run as root or UID/GID 10002 to provision the attempt'
marker="YCA_CONTEXT_SYNTHETIC_${attempt##*.}_$$"
printf '%s\n' "$marker" > "$attempt/source.txt"
mkdir -m 700 "$attempt/tmp"
cat > "$attempt/fixture.sh" <<'FIXTURE'
#!/bin/sh
set -eu
trap 'exit 0' TERM INT
# Synthetic source and deterministic fake provider. No HTTP, Slack, model, or YCA.
mkdir -p /attempt/storage /attempt/workspace /attempt/output
cp /attempt/source.txt /attempt/storage/provider-request.txt
cp /attempt/storage/provider-request.txt /attempt/output/result.txt
cp /attempt/source.txt /tmp/context-tmp.txt
cp /attempt/source.txt /dev/shm/context-shm.txt
cat /attempt/output/result.txt
: > /attempt/ready
while :; do sleep 1; done
FIXTURE
# Root operators must also make the synthetic inputs readable by the fixture UID.
if [[ $(id -u) == 0 ]]; then
  chown 10002:10002 "$attempt/source.txt" "$attempt/fixture.sh" "$attempt/tmp"
fi
# The fixture is interpreted, not executed from the noexec filesystem.
container=$(docker_local run --detach --pull=never --name "$name" \
  --label "io.yupiik.yca.context-canary=$name" --log-driver=none \
  --network=none --user 10002:10002 --read-only --cap-drop=ALL \
  --security-opt=no-new-privileges --pids-limit=32 --memory=128m --cpus=0.5 \
  --mount "type=bind,src=$attempt,dst=/attempt" \
  --mount "type=bind,src=$attempt/tmp,dst=/tmp" \
  --mount "type=bind,src=$attempt/tmp,dst=/dev/shm" \
  --entrypoint /bin/sh "$image" /attempt/fixture.sh 2>"$attempt/launch.err") || fail 'Disposable canary launch failed; see retained evidence status'
printf 'id=%s\nstatus=launched\n' "$container" >> "$evidence/runtime.txt"
for ((i=0; i<30; i++)); do
  if [[ -f "$attempt/ready" ]]; then break; fi
  sleep 1
done
[[ -f "$attempt/ready" ]] || fail 'Synthetic fixture did not become ready'
[[ $(docker_local inspect --format '{{.HostConfig.LogConfig.Type}}|{{.HostConfig.NetworkMode}}|{{.Config.User}}|{{.HostConfig.ReadonlyRootfs}}' "$container") == 'none|none|10002:10002|true' ]] || fail 'Actual Docker configuration differs'
actual_mounts=$(docker_local inspect --format '{{range .Mounts}}{{.Source}}|{{.Destination}}|{{.Type}}{{println}}{{end}}' "$container" | normalize_mounts)
expected_mounts=$(printf '%s\n' "$attempt|/attempt|bind" "$attempt/tmp|/tmp|bind" "$attempt/tmp|/dev/shm|bind" | normalize_mounts)
[[ "$actual_mounts" == "$expected_mounts" ]] || fail 'Unexpected persistent/extra mount'
docker_local exec "$container" cat /proc/self/mountinfo > "$attempt/mountinfo"
check_mountinfo /attempt "$attempt/mountinfo" || fail 'Actual agent mount fails required tmpfs policy'
check_mountinfo /tmp "$attempt/mountinfo" || fail 'Actual agent temporary mount fails required tmpfs policy'
check_mountinfo /dev/shm "$attempt/mountinfo" || fail 'Actual agent shared-memory mount fails required tmpfs policy'
[[ $(docker_local exec "$container" stat -c '%u:%g:%a' /attempt) == 10002:10002:700 ]] || fail 'Actual agent mount ownership differs'
grep -Fq -- "$marker" "$attempt/storage/provider-request.txt" || fail 'Synthetic provider positive control missing'
grep -Fq -- "$marker" "$attempt/output/result.txt" || fail 'Synthetic result positive control missing'
grep -Fq -- "$marker" "$attempt/tmp/context-tmp.txt" || fail 'Synthetic temporary-file positive control missing'
grep -Fq -- "$marker" "$attempt/tmp/context-shm.txt" || fail 'Synthetic shared-memory-file positive control missing'
if docker_local logs "$container" > "$attempt/docker-logs.txt" 2> "$attempt/docker-logs.err"; then
  [[ ! -s "$attempt/docker-logs.txt" ]] || fail 'Unexpected Docker log output'
else
  grep -Fq 'configured logging driver does not support reading' "$attempt/docker-logs.err" || fail 'Docker log readability could not be established'
fi
if grep -Fq -- "$marker" "$attempt/docker-logs.txt" "$attempt/docker-logs.err"; then
  fail 'Canary found in Docker logs'
fi
# Never print matched lines. A read error fails the scan rather than proving absence.
for path in "$evidence" "${scan_roots[@]}"; do
  scan_status=0
  grep -r -F -l -- "$marker" "$path" > "$attempt/scan.matches" 2> "$attempt/scan.err" || scan_status=$?
  [[ "$scan_status" == 1 ]] || fail 'Persistent canary scan matched or was incomplete'
done
printf 'checks=mount-options,ownership,log-driver,network-none,exclusive-attempt-bind,synthetic-provider-result,persistent-scan\n' >> "$evidence/runtime.txt"
printf '%s\n' 'Disposable checks completed; confirming termination and cleanup before acceptance.'
# EXIT trap confirms termination before removing the attempt and records cleanup outcome.
