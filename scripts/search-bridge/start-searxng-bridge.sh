#!/bin/sh
# Combined runtime: SearXNG (metasearch, container-internal 127.0.0.1:8080)
# plus the Cloudimage bridge (public surface, platform $PORT).
#
# The bridge is the process the platform supervises (it handles SIGTERM,
# so deploys and machine stops drain cleanly). SearXNG runs under a
# watchdog loop instead: if the engine ever crashes it restarts within
# seconds, and the bridge answers 502 for the gap — which the app already
# treats as a transient failure, not a dead backend.
set -u

# Settings: copy the bundled file to a writable path and rotate the Flask
# session secret at every boot. (/etc/searxng is a VOLUME mount point in
# the base image — build-time copies there are unreliable — and /tmp is
# writable regardless of the runtime user.)
SETTINGS_SRC=/app/searxng-settings.yml
SETTINGS_RUN=/tmp/searxng-settings.yml
cp "$SETTINGS_SRC" "$SETTINGS_RUN" 2>/dev/null || SETTINGS_RUN="$SETTINGS_SRC"
sed -i "s/__CLOUDIMAGE_SECRET__/$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9')/" \
  "$SETTINGS_RUN" 2>/dev/null || true
export SEARXNG_SETTINGS_PATH="$SETTINGS_RUN"

# SearXNG must be started from its install directory.
cd /usr/local/searxng

# Engine on the container loopback only — never exposed.
(
  while true; do
    /usr/local/searxng/.venv/bin/granian searx.webapp:app
    echo "[watchdog] $(date) searxng exited rc=$?, restarting in 2s" >&2
    sleep 2
  done
) &

# Hold the public surface closed until the engine answers (granian needs a
# few seconds to import searx before it listens): a search landing in that
# window would 502 and make installed apps invalidate the backend address.
# 20 s ceiling, then start anyway — the watchdog will bring the engine up.
i=0
until wget -q -O /dev/null http://127.0.0.1:8080/healthz 2>/dev/null; do
  i=$((i + 1))
  [ "$i" -ge 40 ] && break
  sleep 0.5
done

exec node /app/server.js
