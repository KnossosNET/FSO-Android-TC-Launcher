# Core test fixtures

These files are checked in and loaded from the test classpath. Unit tests must
not download metadata or require a local `fixtures/` directory.

- `fotg-api.json`: snapshot of
  https://api.fsnebula.org/api/1/mod/json/FotG_R/1.1.1, captured on 2026-11-09.
- `fs2demo-api.json`: snapshot of
  https://api.fsnebula.org/api/1/mod/json/fs2_org_demo/1.10.2, captured on 2026-11-09.

The API snapshots retain mod identity, package selection, dependencies and
archive metadata. Descriptions, images, individual file lists and other unused
fields are omitted. Package names, requirements and archive sizes are preserved
from the API responses so the resolver checks use real metadata.

`engine-flags-console.txt` is a representative Windows console capture with a
version response surrounded by command prompts, exercising `EngineFlags`' JSON
extraction. It is a small authored sample, not a full engine flag listing.
