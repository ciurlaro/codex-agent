#!/bin/bash
set -euo pipefail

bundle=${1:?Central deployment bundle is required}
deployment_name=${2:?Central deployment name is required}
report=${3:?Central deployment status report is required}
: "${MAVEN_CENTRAL_USERNAME:?Maven Central username is required}"
: "${MAVEN_CENTRAL_PASSWORD:?Maven Central password is required}"

authorization=$(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')
upload_response=$(curl --fail-with-body --silent --show-error \
  --request POST \
  --header "Authorization: Bearer $authorization" \
  --form "bundle=@$bundle;type=application/octet-stream" \
  "https://central.sonatype.com/api/v1/publisher/upload?name=$deployment_name&publishingType=AUTOMATIC")
deployment_id=$(printf '%s' "$upload_response" | tr -d '"[:space:]')
test -n "$deployment_id"
unset authorization

mkdir -p "$(dirname "$report")"
for _ in $(seq 1 360); do
  authorization=$(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')
  status=$(curl --fail-with-body --silent --show-error \
    --request POST \
    --header "Authorization: Bearer $authorization" \
    "https://central.sonatype.com/api/v1/publisher/status?id=$deployment_id")
  unset authorization
  printf '%s\n' "$status" > "$report"
  state=$(printf '%s' "$status" | jq -er '.deploymentState')
  case "$state" in
    PUBLISHED) exit 0 ;;
    FAILED) printf '%s\n' "$status" >&2; exit 1 ;;
  esac
  sleep 10
done

echo "Central deployment did not finish within 60 minutes: $deployment_id" >&2
exit 1
