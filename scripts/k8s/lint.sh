#!/usr/bin/env bash
# Static checks for Kubernetes artifacts under deploy/. Needs only Docker; tool versions are pinned here
# and used the same way locally and in CI.
set -euo pipefail

SHELLCHECK_IMAGE=koalaman/shellcheck:v0.11.0
HELM_IMAGE=helmunittest/helm-unittest:4.3.0-1.2.1   # Helm 4.3.0 + helm-unittest 1.2.1 (entrypoint: helm unittest)
KUBECONFORM_IMAGE=ghcr.io/yannh/kubeconform:v0.8.0
TRIVY_IMAGE=aquasec/trivy:0.76.2
KUBERNETES_VERSION=1.34.12                          # newest schemas published for kubeconform
CHART=deploy/helm/tastebuds-core
CI_VALUES=$CHART/ci/ci-values.yaml

for tool in docker git jq; do
  command -v "$tool" >/dev/null || { echo "error: '$tool' is required but not installed" >&2; exit 1; }
done

root="$(git rev-parse --show-toplevel)"
cd "$root"

mount=(-v "$root:/work:ro" -w /work)
helm() { docker run --rm "${mount[@]}" -e HOME=/tmp --entrypoint helm "$HELM_IMAGE" "$@"; }

trivy_out="$(mktemp)"
trap 'rm -f "$trivy_out"' EXIT

echo "==> shellcheck"
docker run --rm "${mount[@]}" "$SHELLCHECK_IMAGE" scripts/k8s/*.sh

echo "==> helm lint"
helm lint --strict "$CHART" -f "$CI_VALUES"

echo "==> required values (chart defaults must not render)"
if helm template "$CHART" >/dev/null 2>&1; then
  echo "error: helm template succeeded with the chart defaults; required values are not enforced" >&2
  exit 1
fi

echo "==> kubeconform (Kubernetes $KUBERNETES_VERSION)"
helm template tastebuds-core "$CHART" -f "$CI_VALUES" \
  | docker run --rm -i "$KUBECONFORM_IMAGE" -strict -summary -kubernetes-version "$KUBERNETES_VERSION" -

echo "==> helm unittest"
# helm-unittest creates a snapshot directory next to the tests, so it runs on a writable copy of the chart.
docker run --rm "${mount[@]}" -e HOME=/tmp --entrypoint sh "$HELM_IMAGE" \
  -c 'cp -r "$1" /tmp/chart && exec helm unittest /tmp/chart' sh "$CHART"

echo "==> trivy misconfiguration"
# The container user must be able to write the report; the repository mount stays read-only.
docker run --rm "${mount[@]}" -v "$trivy_out:/out/report.json" -e HOME=/tmp "$TRIVY_IMAGE" \
  fs --scanners misconfig --severity HIGH,CRITICAL --format json --quiet --output /out/report.json \
  --skip-dirs '**/build' --skip-dirs .gradle --skip-dirs .tools --skip-dirs .kotlin .
jq -e '[.Results[]?.Target | select(test("templates/deployment-api\\.yaml$"))] | length > 0' "$trivy_out" >/dev/null \
  || { echo "error: trivy did not render and scan the chart (no templates/deployment-api.yaml target)" >&2; exit 1; }
failures="$(jq -r '.Results[]? | .Target as $t | .Misconfigurations[]? | select(.Status == "FAIL")
  | "\(.ID) \(.Severity) \(.Title) \($t)"' "$trivy_out")"
if [[ -n "$failures" ]]; then
  echo "$failures" >&2
  exit 1
fi

echo "==> all checks passed"
