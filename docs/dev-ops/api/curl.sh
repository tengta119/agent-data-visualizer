#!/usr/bin/env bash
set -euo pipefail

: "${OPEN_AI_KEY:?Set OPEN_AI_KEY to your model API key}"
: "${MODEL_BASE_URL:?Set MODEL_BASE_URL to your OpenAI-compatible API base URL}"

curl "${MODEL_BASE_URL%/}/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${OPEN_AI_KEY}" \
  -d '{"model":"your-model-name","messages":[{"role":"user","content":"1+1"}]}'
