# Hermey Demo

A local demo harness for the Hermey Android app. It runs the **real renderer**
(the same Vite bundle the APK loads) against a **mock Hermes gateway**, so you
can see and screenshot the full app UI with live data — without a device, an
emulator, or a real backend.

## What's here

- `demo_gateway.py` — a mock Hermes remote gateway (aiohttp) speaking the real
  protocol: REST endpoints (`/api/status`, `/api/sessions`,
  `/api/profiles/sessions/sidebar`, `/api/skills`, `/api/toolsets`,
  `/api/config`, `/api/model/*`, `/api/cron/*`, …) **plus a WebSocket JSON-RPC
  gateway** at `/api/ws` that answers `client.capabilities`, `setup.status`,
  `setup.runtime_check`, `session.list`, `session.resume` (full transcript),
  `prompt.submit` (streams `message.delta` / `message.complete`), the
  `gateway.ping` heartbeat, and 25+ other RPCs.
- `shots/` — screenshots captured from the running demo (transcript with tool
  calls, Capabilities, Artifacts, Scheduled jobs, Settings, dark theme, etc.).

## Run it

```bash
# 1. build the renderer (from app/)
cd app && npm install && NODE_OPTIONS=--max-old-space-size=8192 npm run build

# 2. create the demo harness page (bridge + CSS links injected)
#    app/dist/demo.html is generated for this; see app/dist/demo.html

# 3. start the mock gateway
python3 demo/demo_gateway.py --port 8801

# 4. serve the built bundle
cd app/dist && python3 -m http.server 8899

# 5. open http://127.0.0.1:8899/demo.html
```

The demo bridge in `demo.html` emulates the Android `HermeyBridgePlugin`:
REST calls go through `fetch` to the gateway, and the renderer opens its own
WebSocket to `ws://127.0.0.1:8801/api/ws` — exactly as it does on-device.

## Notes

- Requires `aiohttp` and `aiohttp-cors` (`pip install aiohttp aiohttp-cors`).
- The mock answers unknown RPCs with an empty object (logged as "unmodeled")
  so the renderer's boot sequence never blocks; a real gateway answers
  `-32601` for genuinely missing methods.
- Endpoint/RPC shapes mirror `apps/shared/src/gateway-contract.generated.ts`
  and the renderer's `api/` modules — if a surface shows "Failed to fetch",
  the mock is missing that endpoint; add it to `make_app()`.
