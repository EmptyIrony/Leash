#!/bin/bash
# Runs on the Claude Code host (this Ubuntu box). Started by the Mac bridge over SSH;
# speaks ACP on stdin/stdout. Uses the official claude binary + the user's own login.
set -e
ACP_DIR="${LEASH_CLAUDE_ACP_DIR:-$HOME/.leash/claude-acp}"
export CLAUDE_CODE_EXECUTABLE="${CLAUDE_CODE_EXECUTABLE:-$(command -v claude || echo "$HOME/.local/bin/claude")}"
unset ANTHROPIC_API_KEY   # use the subscription login, not an API key that may be in the env
cd "${LEASH_CLAUDE_CWD:-$HOME/workspace}" 2>/dev/null || cd "$HOME"
exec node "$ACP_DIR/node_modules/@agentclientprotocol/claude-agent-acp/dist/index.js"
