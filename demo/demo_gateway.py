#!/usr/bin/env python3
"""
Hermey demo gateway — a faithful mock of the Hermes remote gateway so the
Android app's real renderer can be screenshotted with live data.

Speaks the real protocol the renderer expects:
  - REST:  /api/status, /api/sessions, /api/sessions/{id}, /api/config, ...
  - WS:    /api/ws?token=...  JSON-RPC 2.0 (client.capabilities, prompt.submit,
           session.history, ...) + server->client event notifications
           (gateway.ready, message.delta, message.complete, session.info).

Run:  python3 demo_gateway.py [--port 8801]
"""
import argparse
import asyncio
import json
import time
import uuid

from aiohttp import web, WSMsgType  # noqa

# ── Seed data ────────────────────────────────────────────────────────────────

NOW = int(time.time())

SESSIONS = [
    {
        "id": "sess-demo-1",
        "title": "Refactor the auth middleware",
        "preview": "I'll start by reading the current middleware…",
        "started_at": NOW - 3600,
        "message_count": 12,
        "source": "gateway",
        "cwd": "/home/dev/project",
        "profile": "default",
    },
    {
        "id": "sess-demo-2",
        "title": "Debug the flaky integration test",
        "preview": "The test fails ~10% of the time, usually on the CI runner…",
        "started_at": NOW - 7200,
        "message_count": 8,
        "source": "gateway",
        "cwd": "/home/dev/project",
        "profile": "default",
    },
    {
        "id": "sess-demo-3",
        "title": "Write the migration plan doc",
        "preview": "Here's a phased plan for the database migration…",
        "started_at": NOW - 86400,
        "message_count": 5,
        "source": "gateway",
        "cwd": "/home/dev/docs",
        "profile": "default",
    },
    {
        "id": "sess-demo-4",
        "title": "Set up the staging environment",
        "preview": "Staging is up on the new cluster…",
        "started_at": NOW - 172800,
        "message_count": 20,
        "source": "gateway",
        "cwd": "/home/dev/infra",
        "profile": "default",
    },
]

DEMO_TRANSCRIPT = [
    {
        "role": "user",
        "text": "Can you refactor the auth middleware to use the new token format?",
        "timestamp": NOW - 3600,
        "row_id": 1,
    },
    {
        "role": "assistant",
        "text": (
            "I'll refactor the auth middleware. Let me start by reading the current "
            "implementation to understand the token flow.\n\n"
            "Looking at `middleware/auth.ts`, the current format is a signed JWT with "
            "`iss`/`exp` claims. The new format adds an `aud` claim and switches the "
            "signing key rotation.\n\n"
            "Here's the plan:\n"
            "1. Read the current middleware\n"
            "2. Update the token validation to check `aud`\n"
            "3. Add key-rotation support\n"
            "4. Run the auth test suite"
        ),
        "timestamp": NOW - 3595,
        "row_id": 2,
    },
    {
        "role": "tool",
        "name": "read_file",
        "args": {"path": "middleware/auth.ts"},
        "text": "export async function authMiddleware(req, res, next) {\n  const token = req.headers.authorization?.split(' ')[1];\n  if (!token) return res.status(401).json({ error: 'missing token' });\n  const claims = verifyJwt(token, SECRET);\n  if (claims.exp < Date.now()) return res.status(401).json({ error: 'expired' });\n  req.user = claims;\n  next();\n}",
        "timestamp": NOW - 3590,
        "row_id": 3,
    },
    {
        "role": "assistant",
        "text": (
            "Now I'll rewrite it with the new token format. The key changes:\n\n"
            "- Validate the `aud` claim against the expected audience\n"
            "- Support multiple signing keys for rotation\n"
            "- Keep backward compatibility during the rollout window"
        ),
        "timestamp": NOW - 3585,
        "row_id": 4,
    },
    {
        "role": "tool",
        "name": "edit_file",
        "args": {"path": "middleware/auth.ts"},
        "text": "// Updated: aud claim validation + key rotation\nconst KEYS = loadKeys();\nexport async function authMiddleware(req, res, next) {\n  const token = req.headers.authorization?.split(' ')[1];\n  const claims = verifyAny(token, KEYS);\n  if (claims.aud !== EXPECTED_AUD) return res.status(401).json({ error: 'bad audience' });\n  req.user = claims;\n  next();\n}",
        "timestamp": NOW - 3580,
        "row_id": 5,
    },
    {
        "role": "assistant",
        "text": "The refactor is done and the auth test suite passes (14/14). The middleware now validates the `aud` claim and supports key rotation.",
        "timestamp": NOW - 3575,
        "row_id": 6,
    },
]

REPLY_TEXT = (
    "Here's what I found when I looked into that. The root cause is a race condition "
    "in the connection pool: under load, two requests can grab the same connection "
    "before either marks it busy. The fix is to hold the pool lock across the "
    "checkout-and-mark sequence, which I've done in `pool.ts`.\n\n"
    "I also added a regression test that reproduces the flake deterministically by "
    "forcing the interleaving with a barrier. All 47 tests pass."
)

SKILLS = [
    {"name": "web-search", "enabled": True, "description": "Search the web"},
    {"name": "code-review", "enabled": True, "description": "Review code changes"},
    {"name": "git-ops", "enabled": True, "description": "Git worktree and branch management"},
    {"name": "browser-use", "enabled": False, "description": "Drive a browser"},
]

TOOLSETS = [
    {"name": "core", "enabled": True, "description": "Core file and shell tools"},
    {"name": "web", "enabled": True, "description": "Web fetch and search"},
    {"name": "computer-use", "enabled": False, "description": "Desktop automation"},
]

CONFIG = {
    "model": "hermes-4-405b",
    "model_context_length": 131072,
    "display": {"personality": "default", "show_reasoning": True},
    "memory": {"memory_enabled": True, "memory_char_limit": 8000},
    "approvals": {"mode": "ask", "timeout": 120},
    "terminal": {"cwd": "/home/dev/project"},
}


def profile_list():
    return {
        "profiles": [
            {
                "name": "default",
                "label": "Default",
                "active": True,
                "model": "hermes-4-405b",
                "session_count": len(SESSIONS),
            }
        ]
    }


# ── REST handlers ────────────────────────────────────────────────────────────

async def handle_status(request):
    return web.json_response(
        {
            "status": "ok",
            "version": "1.0.0-demo",
            "install_id": "hermey-demo-install",
            "profiles": ["default"],
            "uptime_s": 12345,
        }
    )


async def handle_sessions(request):
    return web.json_response(
        {
            "sessions": SESSIONS,
            "total": len(SESSIONS),
            "offset": 0,
            "limit": 100,
        }
    )


async def handle_session_detail(request):
    sid = request.match_info["id"]
    session = next((s for s in SESSIONS if s["id"] == sid), None)
    if not session:
        return web.json_response({"detail": "Session not found"}, status=404)
    return web.json_response({**session, "messages": DEMO_TRANSCRIPT})


async def handle_session_messages(request):
    # Paginated transcript page (SessionMessagesResponse). The artifacts index
    # and the transcript view both read this; return the demo transcript.
    sid = request.match_info["id"]
    limit = int(request.query.get("limit", "50"))
    offset = int(request.query.get("offset", "0"))
    order = request.query.get("order", "oldest")
    messages = DEMO_TRANSCRIPT if order != "latest" else list(reversed(DEMO_TRANSCRIPT))
    page = messages[offset : offset + limit]
    return web.json_response(
        {
            "profile": "default",
            "messages": page,
            "pagination": {
                "limit": limit,
                "offset": offset,
                "order": order,
                "returned": len(page),
            },
            "session_id": sid,
        }
    )


async def handle_config(request):
    return web.json_response(CONFIG)


async def handle_skills(request):
    return web.json_response(SKILLS)


async def handle_toolsets(request):
    return web.json_response(TOOLSETS)


async def handle_profiles(request):
    return web.json_response(profile_list())


async def handle_sidebar(request):
    # The batched sidebar slice the renderer loads: recents + cron + messaging.
    recents = [s for s in SESSIONS if s.get("source") != "cron"]
    return web.json_response(
        {
            "recents": {"sessions": recents, "profiles_truncated": {"default": False}},
            "cron": {"sessions": []},
            "messaging": {"sessions": []},
        }
    )


async def handle_models(request):
    return web.json_response(
        {
            "models": [
                {"id": "hermes-4-405b", "label": "Hermes 4 405B", "context": 131072},
                {"id": "hermes-4-70b", "label": "Hermes 4 70B", "context": 131072},
            ]
        }
    )


async def handle_cron(request):
    return web.json_response(
        {
            "jobs": [
                {
                    "id": "cron-1",
                    "name": "Nightly backup check",
                    "schedule": "0 2 * * *",
                    "enabled": True,
                    "last_run": NOW - 3600 * 8,
                    "next_run": NOW + 3600 * 16,
                }
            ]
        }
    )


async def handle_active_profile(request):
    return web.json_response({"profile": "default", "name": "default"})


async def handle_model_info(request):
    return web.json_response(
        {
            "model": "hermes-4-405b",
            "provider": "nous",
            "context_length": 131072,
            "max_output": 8192,
            "supports_vision": True,
            "supports_tools": True,
        }
    )


async def handle_update_check(request):
    return web.json_response(
        {
            "update_available": False,
            "current_version": "1.0.0-demo",
            "latest_version": "1.0.0-demo",
            "channel": "stable",
        }
    )


async def handle_voice_status(request):
    return web.json_response({"available": False, "mode": "disabled"})


async def handle_config_defaults(request):
    return web.json_response(CONFIG)


async def handle_artifacts(request):
    return web.json_response({"artifacts": []})


async def handle_mcp_servers(request):
    return web.json_response({"servers": []})


async def handle_connectors(request):
    return web.json_response({"connectors": []})


async def handle_notifications_config(request):
    return web.json_response({"enabled": True})


async def handle_session_timeline(request):
    return web.json_response({"events": [], "session_id": request.match_info["id"]})


async def handle_git_status(request):
    return web.json_response(
        {
            "branch": "main",
            "default_branch": "main",
            "detached": False,
            "ahead": 0,
            "behind": 0,
            "staged": 0,
            "unstaged": 0,
            "untracked": 0,
            "conflicted": 0,
            "changed": 0,
            "added": 0,
            "removed": 0,
            "files": [],
        }
    )


async def handle_tools_toolsets(request):
    return web.json_response(TOOLSETS)


async def handle_config_schema(request):
    return web.json_response({"fields": {}, "sections": []})


async def handle_cron_jobs(request):
    return web.json_response(
        [
            {
                "id": "cron-1",
                "name": "Nightly backup check",
                "enabled": True,
                "prompt": "Check backups and report status",
                "schedule": {"kind": "cron", "expr": "0 2 * * *", "display": "Every day at 2:00 AM"},
                "schedule_display": "Every day at 2:00 AM",
                "last_run_at": NOW - 3600 * 8,
                "next_run_at": NOW + 3600 * 16,
                "last_error": None,
                "state": "idle",
            }
        ]
    )


async def handle_cron_blueprints(request):
    return web.json_response({"blueprints": []})


async def handle_cron_delivery_targets(request):
    return web.json_response({"targets": []})


async def handle_elevenlabs_voices(request):
    return web.json_response({"voices": []})


async def handle_model_options(request):
    return web.json_response(
        {
            "models": [
                {"id": "hermes-4-405b", "label": "Hermes 4 405B", "context": 131072, "provider": "nous"},
                {"id": "hermes-4-70b", "label": "Hermes 4 70B", "context": 131072, "provider": "nous"},
                {"id": "deepseek-v4", "label": "DeepSeek V4", "context": 65536, "provider": "deepseek"},
            ],
            "reasoning_efforts": ["low", "medium", "high"],
            "service_tiers": ["auto", "default"],
        }
    )


async def handle_model_auxiliary(request):
    return web.json_response(
        {
            "main": {"model": "hermes-4-405b", "provider": "nous"},
            "tasks": [],
        }
    )


async def handle_model_moa(request):
    return web.json_response(
        {
            "default_preset": "default",
            "active_preset": "default",
            "presets": {},
            "aggregator": {"model": "hermes-4-405b", "provider": "nous"},
            "aggregator_temperature": 0.7,
            "degraded_reference_policy": "loud",
            "enabled": False,
            "reference_models": [],
            "reference_temperature": 0.7,
            "reference_timeout": None,
        }
    )


# ── WebSocket JSON-RPC gateway ───────────────────────────────────────────────

async def handle_ws(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)

    active_session = None

    async def send_event(event_type, payload, session_id=None):
        await ws.send_str(
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "method": "event",
                    "params": {"type": event_type, "payload": payload, "session_id": session_id},
                }
            )
        )

    async def reply(req_id, result):
        await ws.send_str(json.dumps({"jsonrpc": "2.0", "id": req_id, "result": result}))

    async def reply_error(req_id, code, message):
        await ws.send_str(
            json.dumps({"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}})
        )

    # On connect: announce gateway.ready so the renderer finishes booting.
    print("[ws] client connected", flush=True)
    await send_event(
        "gateway.ready",
        {
            "skin": {"name": "nous", "mode": "dark"},
            "change_events": True,
            "replay_epoch": uuid.uuid4().hex[:8],
        },
    )

    async for msg in ws:
        if msg.type != WSMsgType.TEXT:
            continue

        try:
            frame = json.loads(msg.data)
        except json.JSONDecodeError:
            continue

        req_id = frame.get("id")
        method = frame.get("method")
        params = frame.get("params") or {}

        if method == "event":
            continue

        print(f"[ws] <- {method} {json.dumps(params)[:120]}", flush=True)

        # ── JSON-RPC method dispatch ──────────────────────────────────────
        if method == "gateway.ping":
            # Heartbeat: echo the id back so the transport stays alive.
            await ws.send_str(json.dumps({"jsonrpc": "2.0", "id": req_id, "result": {"pong": True}}))

        elif method == "client.capabilities":
            await reply(
                req_id,
                {
                    "protocol_version": 1,
                    "capabilities": ["streaming", "tools", "clarify", "approvals"],
                    "server_requests": True,
                },
            )

        elif method == "setup.status":
            await reply(
                req_id,
                {
                    "provider_configured": True,
                    "ready": True,
                    "free_tier_account": False,
                    "free_tier_route": None,
                    "other_providers": [],
                    "inference_provider": "nous",
                    "profile": "default",
                    "ok": True,
                    "error": None,
                    "error_code": None,
                    "retryable": False,
                    "retry_after": None,
                },
            )

        elif method == "setup.runtime_check":
            await reply(
                req_id,
                {
                    "ok": True,
                    "provider": "nous",
                    "model": "hermes-4-405b",
                    "source": "config",
                    "error": None,
                    "free_tier_route": None,
                    "profile": "default",
                },
            )

        elif method == "projects.tree":
            await reply(
                req_id,
                {
                    "projects": [
                        {
                            "id": "proj-1",
                            "name": "project",
                            "path": "/home/dev/project",
                            "session_ids": [s["id"] for s in SESSIONS[:2]],
                        },
                        {
                            "id": "proj-2",
                            "name": "docs",
                            "path": "/home/dev/docs",
                            "session_ids": [SESSIONS[2]["id"]],
                        },
                    ],
                    "active_id": "proj-1",
                    "scoped_session_ids": [s["id"] for s in SESSIONS],
                },
            )

        elif method == "pet.info":
            await reply(req_id, {"enabled": False})

        elif method == "wake.status":
            await reply(
                req_id,
                {
                    "listening": False,
                    "owned_by_caller": False,
                    "owner_surface": None,
                    "phrase": "hey hermes",
                    "provider": None,
                    "configured_surface": None,
                    "input_device": None,
                    "available": False,
                    "hint": None,
                    "enabled": False,
                    "audio_silent": True,
                    "capture": None,
                    "local_input_available": False,
                    "sample_rate": 16000,
                    "frame_length": 512,
                },
            )

        elif method == "session.active_list":
            await reply(req_id, {"sessions": SESSIONS})

        elif method == "i18n.languages":
            await reply(
                req_id,
                {
                    "languages": [
                        {"code": "en", "label": "English"},
                        {"code": "es", "label": "Español"},
                        {"code": "fr", "label": "Français"},
                        {"code": "de", "label": "Deutsch"},
                        {"code": "ja", "label": "日本語"},
                        {"code": "zh", "label": "中文"},
                    ]
                },
            )

        elif method in ("shared_metrics.status", "shared_metrics.update_run"):
            await reply(req_id, {"enabled": False, "send": False, "decided": True, "reask": False, "ok": True})

        elif method == "free_tier.status":
            await reply(
                req_id,
                {
                    "account": None,
                    "signed_in": False,
                    "route": None,
                    "challenge_required": False,
                    "tokens_remaining": None,
                },
            )

        elif method == "model.options":
            await reply(
                req_id,
                {
                    "models": [
                        {"id": "hermes-4-405b", "label": "Hermes 4 405B", "context": 131072, "provider": "nous"},
                        {"id": "hermes-4-70b", "label": "Hermes 4 70B", "context": 131072, "provider": "nous"},
                        {"id": "deepseek-v4", "label": "DeepSeek V4", "context": 65536, "provider": "deepseek"},
                    ],
                    "reasoning_efforts": ["low", "medium", "high"],
                    "service_tiers": ["auto", "default"],
                },
            )

        elif method == "session.list":
            await reply(req_id, {"sessions": SESSIONS, "total": len(SESSIONS)})

        elif method == "session.most_recent":
            await reply(req_id, {"session_id": SESSIONS[0]["id"] if SESSIONS else None})

        elif method == "session.history":
            await reply(req_id, {"count": len(DEMO_TRANSCRIPT), "messages": DEMO_TRANSCRIPT})

        elif method == "session.info":
            sid = params.get("session_id") or (SESSIONS[0]["id"] if SESSIONS else None)
            session = next((s for s in SESSIONS if s["id"] == sid), None)
            await reply(req_id, session or {})

        elif method == "session.resume":
            sid = params.get("session_id") or SESSIONS[0]["id"]
            active_session = sid
            session = next((s for s in SESSIONS if s["id"] == sid), None)
            await reply(
                req_id,
                {
                    "session_id": sid,
                    "message_count": len(DEMO_TRANSCRIPT),
                    "messages": DEMO_TRANSCRIPT,
                    "info": session or {},
                    "stored_session_id": sid,
                    "resumed": True,
                    "session_key": sid,
                    "messages_omitted": 0,
                    "hydrating": False,
                    "running": False,
                    "turn_started_at": None,
                    "started_at": (session or {}).get("started_at"),
                    "status": "idle",
                    "inflight": [],
                    "queued": [],
                    "pending_approval": None,
                    "open_requests": [],
                    "pending_connection": None,
                    "todo_state": None,
                    "auto_continue": False,
                },
            )

        elif method == "session.create":
            new_id = f"sess-{uuid.uuid4().hex[:8]}"
            new_session = {
                "id": new_id,
                "title": "New session",
                "preview": "",
                "started_at": int(time.time()),
                "message_count": 0,
                "source": "gateway",
                "profile": "default",
            }
            SESSIONS.insert(0, new_session)
            active_session = new_id
            await reply(req_id, {"session_id": new_id})

        elif method == "profiles.list":
            await reply(req_id, profile_list())

        elif method == "config.get":
            await reply(req_id, CONFIG)

        elif method == "prompt.submit":
            sid = params.get("session_id") or active_session or SESSIONS[0]["id"]
            active_session = sid
            text = params.get("text") or params.get("prompt") or ""

            # Stream a realistic reply: start -> deltas -> complete.
            await send_event("message.start", {}, sid)
            await asyncio.sleep(0.3)
            words = REPLY_TEXT.split(" ")
            for i in range(0, len(words), 3):
                chunk = " ".join(words[i : i + 3]) + " "
                await send_event("message.delta", {"text": chunk, "verbose": False}, sid)
                await asyncio.sleep(0.05)
            await send_event(
                "message.complete",
                {
                    "text": REPLY_TEXT,
                    "status": "complete",
                    "usage": {"input_tokens": 120, "output_tokens": 85},
                },
                sid,
            )
            await reply(req_id, {"ok": True, "session_id": sid})

        elif method in ("session.interrupt", "session.steer"):
            await reply(req_id, {"ok": True})

        elif method == "skills.list":
            await reply(req_id, {"skills": SKILLS})

        elif method == "toolsets.list":
            await reply(req_id, {"toolsets": TOOLSETS})

        elif method == "commands.catalog":
            await reply(
                req_id,
                {"pairs": [], "sub": {}, "canon": {}, "commands": [], "categories": [], "skills": [], "skill_count": 0, "warning": None},
            )

        elif method == "subagent.list":
            await reply(req_id, {"subagents": [], "delegations": []})

        elif method == "process.list":
            await reply(req_id, {"processes": []})

        elif method == "session.control" or method == "session.control.read" or method.startswith("session.control."):
            # session.control: the action rides as a method suffix
            # (session.control.read / .write). Answer the snapshot shape.
            await reply(
                req_id,
                {
                    "control": {
                        "goal": None,
                        "loop": None,
                        "heartbeat": None,
                        "revision": "0",
                        "updated_at": NOW,
                    },
                    "dispatch": {"type": None, "output": None, "notice": None, "message": None, "display": None},
                },
            )

        else:
            # Unknown method. A real gateway answers -32601, but for the
            # DEMO we answer an empty object instead so the renderer's boot
            # sequence never blocks on an RPC we didn't model — every
            # surface still renders with real data from the modeled calls.
            # (Log it so we can see what else the renderer asks for.)
            print(f"[ws] (unmodeled, replying empty) {method}", flush=True)
            await reply(req_id, {})

    return ws


# ── App + routes ─────────────────────────────────────────────────────────────

def make_app():
    app = web.Application()
    # CORS: the renderer is served from :8899 and the gateway from :8801.
    # The real gateway sits behind the same origin as its dashboard, but for
    # the demo harness (renderer and gateway on different ports) we allow it.
    import aiohttp_cors

    cors = aiohttp_cors.setup(
        app,
        defaults={"*": aiohttp_cors.ResourceOptions(allow_credentials=False, expose_headers="*", allow_headers="*")},
    )

    def route(method, path, handler):
        resource = cors.add(app.router.add_resource(path))
        cors.add(resource.add_route(method, handler))

    route("GET", "/api/status", handle_status)
    route("GET", "/api/sessions", handle_sessions)
    route("GET", "/api/sessions/{id}/messages", handle_session_messages)
    route("GET", "/api/sessions/{id}/timeline", handle_session_timeline)
    route("GET", "/api/sessions/{id}", handle_session_detail)
    route("GET", "/api/profiles/sessions/sidebar", handle_sidebar)
    route("GET", "/api/git/status", handle_git_status)
    route("GET", "/api/tools/toolsets", handle_tools_toolsets)
    route("GET", "/api/config/schema", handle_config_schema)
    route("GET", "/api/cron/jobs", handle_cron_jobs)
    route("GET", "/api/cron/blueprints", handle_cron_blueprints)
    route("GET", "/api/cron/delivery-targets", handle_cron_delivery_targets)
    route("GET", "/api/audio/elevenlabs/voices", handle_elevenlabs_voices)
    route("GET", "/api/config", handle_config)
    route("GET", "/api/skills", handle_skills)
    route("GET", "/api/toolsets", handle_toolsets)
    route("GET", "/api/profiles", handle_profiles)
    route("GET", "/api/models", handle_models)
    route("GET", "/api/cron", handle_cron)
    route("GET", "/api/profiles/active", handle_active_profile)
    route("GET", "/api/model/info", handle_model_info)
    route("GET", "/api/model/options", handle_model_options)
    route("GET", "/api/model/auxiliary", handle_model_auxiliary)
    route("GET", "/api/model/moa", handle_model_moa)
    route("GET", "/api/hermes/update/check", handle_update_check)
    route("GET", "/api/audio/voice-live/status", handle_voice_status)
    route("GET", "/api/config/defaults", handle_config_defaults)
    route("GET", "/api/artifacts", handle_artifacts)
    route("GET", "/api/mcp/servers", handle_mcp_servers)
    route("GET", "/api/connectors", handle_connectors)
    route("GET", "/api/notifications/config", handle_notifications_config)
    app.router.add_get("/api/ws", handle_ws)
    return app


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8801)
    args = parser.parse_args()
    print(f"Demo gateway on http://127.0.0.1:{args.port}  (ws: /api/ws)")
    web.run_app(make_app(), host="127.0.0.1", port=args.port, print=None)
