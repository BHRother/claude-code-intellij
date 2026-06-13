# Tier 2 spike — streaming-input session (`feature/streaming-session`)

Goal: evaluate replacing our one-shot `claude -p "<prompt>"` per turn with a **single
long-lived** process per session driven over stdin, to unlock true mid-turn `/btw`
queueing, live steering/interrupt, and faster turns (no cold start, no `--resume`).

## Command

```
claude -p --input-format stream-json --output-format stream-json --verbose \
       --permission-mode <mode>
```

Plain pipes work (stdin=PIPE, stdout=PIPE) — no PTY/`script` wrapper needed; stream-json
output is flushed per event.

## Messages we send (stdin, newline-delimited JSON)

- **User turn:**
  ```json
  {"type":"user","message":{"role":"user","content":[{"type":"text","text":"..."}]}}
  ```
- **Interrupt the current turn:**
  ```json
  {"type":"control_request","request_id":"req_1","request":{"subtype":"interrupt"}}
  ```
  (Other control subtypes seen in the binary: `set_permission_mode`, `get_context_usage`,
  `read_file`, `file_suggestions`.)

## Output events (stdout) — same stream-json we already parse

`system/init` (per turn) · `assistant` · `user` (tool_result) · `result/success` ·
`result/error_during_execution` (emitted when a turn is interrupted) · `control_response`
(`{"subtype":"success","request_id":"…"}`) · `rate_limit_event` · `stream_event`
(with `--include-partial-messages`).

## Observed behavior (two spikes)

1. **Persistence + queueing.** One process handled msg1 then msg2. A message sent **mid-turn**
   was **queued**: msg1 ran to completion (`result/success`), then a fresh `system/init` and
   msg2 ran. Not interleaved, not dropped. → This is exactly `/btw`'s "queue, don't interrupt."
2. **Interrupt.** Sending the interrupt control_request mid-turn returned `control_response`
   success immediately and **cut the running turn short** (`result/error_during_execution` after
   a partial assistant message). A follow-up user message then started a new turn normally —
   the session survives the interrupt and can be steered.

## Conclusion — viable. Proposed Tier 2 design

- **`StreamingClaudeSession`** (alongside the current `ClaudeSession`, feature-flagged):
  spawns the persistent process, writes user messages to stdin, reads stream-json on a reader
  thread, **reuses `parseStreamLine`** for output (system/assistant/user/result/...).
- **Conversation state** lives in the process — drop per-turn `--resume`.
- **Queue / `/btw`**: just write the next message; the CLI queues it. (Tier 1's plugin queue
  becomes optional once this is in.)
- **Interrupt / steer**: a Stop-and-redirect control via the interrupt request.
- **Lifecycle**: handle process exit/restart, `result/error_during_execution`, rate-limit
  events, and `set_permission_mode` for mid-session mode changes.

Open questions for implementation: tool-permission prompts in streaming mode, partial-message
rendering, and graceful restart on crash. To be built incrementally on this branch.
