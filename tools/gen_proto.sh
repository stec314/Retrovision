#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 stec314 and the Retrovision contributors
# Regenerate nanopb C sources from proto/. Commit the output.
# CI runs this and fails if the working tree changes (generated code drift).
# Requires: pip install nanopb==$(cat firmware/components/nanopb/VERSION)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/firmware/components/rv_proto"
want="$(cat "$ROOT/firmware/components/nanopb/VERSION")"
have="$(python3 -c 'import importlib.metadata as m; print(m.version("nanopb"))')"
if [ "$want" != "$have" ]; then
  echo "nanopb generator $have != runtime $want; pip install nanopb==$want" >&2
  exit 1
fi
cd "$ROOT/proto/retrovision/v1"
nanopb_generator -q -D "$OUT" retrovision.proto
echo "generated into ${OUT#$ROOT/}"
