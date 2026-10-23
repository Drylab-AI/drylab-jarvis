# Server local test (Phase 4)

Tested on a free port (8799) because another local app already holds 8765.

```
GET /health            -> {"ok":true}
POST /upload (valid)    -> {"id":"5e85d783408e","path":"2026-10-23/...m4a","bytes":20000}
POST /upload (empty)    -> 400
POST /upload device=../../etc/evil -> written safely as "etc-evil" inside recordings/
```

Sidecar JSON verified with id, device, started_at, duration_ms, bytes, received_at, content_type.
