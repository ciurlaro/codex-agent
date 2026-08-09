#!/usr/bin/env bash
set -euo pipefail

mode=${1:?validate or release}
bundle=${2:?bundle path}
candidate=${3:?candidate manifest path}
record=${4:?deployment record path}
api=${CENTRAL_PORTAL_API:-https://central.sonatype.com/api/v1/publisher}
test "$mode" = validate -o "$mode" = release
test -f "$bundle" -a -f "$candidate"
: "${MAVEN_CENTRAL_USERNAME:?missing Central username}"
: "${MAVEN_CENTRAL_PASSWORD:?missing Central password}"

bundle_sha=$(shasum -a 256 "$bundle" | awk '{print $1}')
candidate_sha=$(shasum -a 256 "$candidate" | awk '{print $1}')
version=$(jq -er '.version' "$candidate")
commit=$(jq -er '.candidateCommit' "$candidate")
name="codex-agent-${version}-${commit:0:12}-${bundle_sha:0:12}"
auth=$(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64)

write_record() {
  local id=$1 state=$2
  mkdir -p "$(dirname "$record")"
  jq -n --arg id "$id" --arg name "$name" --arg state "$state" \
    --arg candidateSha256 "$candidate_sha" --arg bundleSha256 "$bundle_sha" \
    '{schemaVersion:1,deploymentId:$id,deploymentName:$name,deploymentState:$state,candidateManifestSha256:$candidateSha256,bundleSha256:$bundleSha256}' \
    > "$record.tmp"
  mv "$record.tmp" "$record"
}

if test -f "$record"; then
  test "$(jq -er '.deploymentName' "$record")" = "$name"
  test "$(jq -er '.candidateManifestSha256' "$record")" = "$candidate_sha"
  test "$(jq -er '.bundleSha256' "$record")" = "$bundle_sha"
  deployment_id=$(jq -er '.deploymentId' "$record")
else
  test "$mode" = validate
  deployment_id=$(curl --fail-with-body --silent --show-error \
    -H "Authorization: Bearer $auth" \
    -F "bundle=@$bundle;type=application/octet-stream" \
    "$api/upload?publishingType=USER_MANAGED&name=$name")
  test -n "$deployment_id"
  write_record "$deployment_id" PENDING
fi

poll_until() {
  local wanted=$1 state response
  for _ in $(seq 1 120); do
    response=$(curl --fail-with-body --silent --show-error -X POST \
      -H "Authorization: Bearer $auth" "$api/status?id=$deployment_id")
    state=$(printf '%s' "$response" | jq -er '.deploymentState')
    write_record "$deployment_id" "$state"
    case "$state" in
      FAILED) printf '%s\n' "$response" >&2; return 1 ;;
      PUBLISHED) return 0 ;;
      "$wanted") return 0 ;;
    esac
    sleep 10
  done
  echo "Central deployment timed out waiting for $wanted" >&2
  return 1
}

poll_until VALIDATED
state=$(jq -er '.deploymentState' "$record")
if test "$mode" = release -a "$state" != PUBLISHED; then
  test "$state" = VALIDATED
  curl --fail-with-body --silent --show-error -X POST \
    -H "Authorization: Bearer $auth" "$api/deployment/$deployment_id"
  poll_until PUBLISHED
fi
