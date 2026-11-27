#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$ROOT"

# The normal reactor install also renders and validates the aggregated site.
mvn spotless:apply clean install -T1C
for module in coding-agent/yca coding-agent/extensions/notifications-service coding-agent/extensions/sandbox-helper; do
  (
    cd -- "$ROOT/$module"
    mvn arthur:native-image -Pnative
  )
done

# ensure JSON-Schema is up to date
mvn -pl documentation yupiik-tools:minisite@minisite

# Stage complete replacements, never overwrite a running executable in place.
BIN="$ROOT/generated/bin"
install -d -m 700 -- "$BIN"
STAGING=$(mktemp -d "$BIN/.rebuild.XXXXXX")
trap 'rm -rf -- "$STAGING"' EXIT
install -m 755 "$ROOT/coding-agent/yca/target/yca-bin.graal.bin" "$STAGING/yca"
install -m 755 "$ROOT/coding-agent/extensions/notifications-service/target/notifications-service.graal.bin" \
  "$STAGING/notifications-service"
install -m 755 "$ROOT/coding-agent/extensions/sandbox-helper/target/sandbox-helper.graal.bin" \
  "$STAGING/sandbox-helper"
# Probe only a disposable helper process; never restrict this build shell.
"$STAGING/sandbox-helper" --probe
mv -f -- "$STAGING/sandbox-helper" "$BIN/sandbox-helper"
mv -f -- "$STAGING/yca" "$BIN/yca"
mv -f -- "$STAGING/notifications-service" "$BIN/notifications-service"

# Restart the tray after staging, or start it if it is currently stopped.
systemctl --user restart yca-notifications.service

scp -q "$BIN/yca" \
  VS144@LIN0176875:/home/VS144/Installations/yca
scp -q "$BIN/sandbox-helper" \
  VS144@LIN0176875:/home/VS144/Installations/sandbox-helper
