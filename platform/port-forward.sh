#!/usr/bin/env bash
# Port-forward the downstream eGov services the modulith still calls over HTTP.
# The converted edges (idgen, localization) are in-process and do NOT need these;
# these are for individual's remaining HTTP calls (user/boundary/mdms/enc).
#
# Local ports must match application.properties:
#   egov.user.host=http://localhost:8281
#   egov.boundary.host=http://localhost:8282
#   egov.mdms.host / mdms.service.host=http://localhost:8283
#   egov.enc.host=http://localhost:8284
set -euo pipefail

pf() { kubectl port-forward -n egov "svc/$1" "$2:$3" >/tmp/pf-$1.log 2>&1 & echo "  $1 -> localhost:$2 (pid $!)"; }

echo "starting port-forwards..."
pf egov-user        8281 8080
pf boundary-service 8282 8080
pf mdms-v2          8283 8080
pf egov-enc-service 8284 8080
echo "done. stop with: pkill -f 'kubectl port-forward -n egov'"
