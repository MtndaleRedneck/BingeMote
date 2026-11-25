#!/usr/bin/env bash
# DRY-RUN BRANCH ONLY: the workflow must carry no external write capability.
set -u; f=.github/workflows/build.yml; bad=0
for pat in 'TESTLAB_PAT' 'gh release' 'GH_TOKEN' 'contents: write' 'gh api' 'Method Delete' 'Method Post' 'curl.* -X (POST|DELETE|PUT|PATCH)' ; do
  if grep -Eq -- "$pat" "$f"; then echo "BAD  dry source contains: $pat"; grep -En -- "$pat" "$f" | head -3; bad=1; fi
done
if grep -Pq 'secrets\.(?!KEYSTORE_B64|KEYSTORE_PASSWORD|GITHUB_TOKEN)' "$f"; then echo 'BAD  dry source references a secret other than the signing keystore / read-only GITHUB_TOKEN'; bad=1; fi
[ $bad -eq 0 ] && echo "DRY SOURCE ASSERT PASS (no PAT, token, release, write permission or secrets reference)" || exit 1
