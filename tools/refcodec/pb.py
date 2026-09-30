"""Compile retrovision.proto on the fly and import it (no generated code in git)."""

from __future__ import annotations

import importlib
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PROTO_ROOT = REPO / "proto"
PROTO_FILE = PROTO_ROOT / "retrovision" / "v1" / "retrovision.proto"


def load():
    from grpc_tools import protoc  # pip install grpcio-tools

    out = Path(tempfile.mkdtemp(prefix="retrovision_pb_"))
    rc = protoc.main(["protoc", f"-I{PROTO_ROOT}", f"--python_out={out}", str(PROTO_FILE)])
    if rc != 0:
        raise RuntimeError("protoc failed")
    sys.path.insert(0, str(out))
    return importlib.import_module("retrovision.v1.retrovision_pb2")
