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

# Rotate the Flask session secret at every boot — the committed
# placeholder must never become the live key.
if [ -w /etc/searxng/settings.yml ]; then
  sed -i "s/__CLOUDIMAGE_SECRET__/$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9')/" \
    /etc/searxng/settings.yml || true
fi

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

exec node /app/server.js
