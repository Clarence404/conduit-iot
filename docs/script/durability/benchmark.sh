#!/bin/sh
# Run after fault tests, without concurrent resets/kills. CSV goes to stdout.
set -eu
payload=$(printf '%0256d' 0)
for mode in everysec always; do
  for clients in 1 16; do
    for round in 1 2 3; do
      printf '# mode=%s clients=%s round=%s\n' "$mode" "$clients" "$round"
      docker exec "conduit-durability-redis-$mode" redis-benchmark --csv -n 10000 -c "$clients" -P 1 XADD benchmark:stream '*' seq 1 payload "$payload"
    done
  done
done
