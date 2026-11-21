# Trivy's Helm scanner skips hook templates, so the migration Job is never misconfiguration-scanned
- **Found during:** 009 (review round 1)
- **Location:** deploy/helm/tastebuds-core/templates/job-migrate.yaml, scripts/k8s/lint.sh (step 6), .github/workflows/ci.yml (`dependencies` job Trivy step)
- **Severity:** low
- **What:** Trivy's Helm scanner leaves out templates with a `helm.sh/hook` annotation. `job-migrate.yaml` is a
  pre-install/pre-upgrade hook, so neither `scripts/k8s/lint.sh` nor the CI Trivy scan checks it. The reviewer
  removed the hook annotations in a scratch copy: the Job then scanned with 0 HIGH/CRITICAL failures, so nothing is
  wrong today. helm-unittest covers its security context (`job_migrate_test.yaml`).
- **Why it matters:** This pod holds the schema owner's credentials. A later regression in its pod security would
  pass the misconfiguration scan.
- **Suggested fix:** In `lint.sh`, also render the chart with `helm template` into a temp directory and run
  `trivy config` on the output (hooks are plain manifests there), with the same severity and jq failure check.
