#!/usr/bin/env bash
# Creates (idempotently) a git worktree of light-sdk pinned to v0.2.7 and wires this repo's tool/ into it.
set -euo pipefail

LIGHT_SDK_DIR="${LIGHT_SDK_DIR:-/Users/davidnwogu/workspace/light-sdk}"
WORKTREE="${WORKTREE:-/Users/davidnwogu/workspace/light-sdk-v0.2.7}"
TAG="v0.2.7"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ ! -d "$WORKTREE" ]; then
  git -C "$LIGHT_SDK_DIR" fetch --tags
  git -C "$LIGHT_SDK_DIR" worktree add --detach "$WORKTREE" "$TAG"
fi

if [ "$(git -C "$WORKTREE" describe --tags --exact-match)" != "$TAG" ]; then
  echo "error: $WORKTREE is not at $TAG" >&2
  exit 1
fi

LINE="project(\":tool\").projectDir = file(\"$REPO_ROOT/tool\")"
if ! grep -qxF "$LINE" "$WORKTREE/settings.gradle.kts"; then
  printf '\n%s\n' "$LINE" >> "$WORKTREE/settings.gradle.kts"
fi
