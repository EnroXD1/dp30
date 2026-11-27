#!/bin/sh
set -eu
umask 077
while true; do
    stamp=$(date -u +%Y%m%dT%H%M%SZ)
    temp="/backup/dp30-$stamp.pending"
    pg_dump --dbname="$DP30_DATABASE_URL" --format=custom --file="$temp"
    pg_restore --list "$temp" >/dev/null
    mv "$temp" "/backup/dp30-$stamp.dump"
    find /backup -maxdepth 1 -name 'dp30-*.dump' -mtime +14 -delete
    sleep 43200
done
