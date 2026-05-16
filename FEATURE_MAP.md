# Claude Code IntelliJ Plugin — Feature Map

Tracks planned Settings/UI additions for surfacing more control over the
`claude` CLI. Each entry: what it does, how it maps to the CLI/env, the
proposed UI control, default, and notes.

## Status legend

- [x] Implemented
- [ ] Planned / spec'd

---

## Inline chip row (input area)

Per-session overrides; global defaults remain in Settings.

| Setting | Status | Maps to | UI | Default | Notes |
|---|---|---|---|---|---|
| Model | [x] | `--model <id>` per spawn | Chip dropdown | from `ClaudeSettings.model` | Empty value = CLI default. Built-in models + any custom IDs the user has used. |
| Permission mode | [x] | `--permission-mode <mode>` per spawn | Chip dropdown | from `ClaudeSettings.permissionMode` | Three modes: `plan` (Plan), `acceptEdits` (Content Only), `bypassPermissions` (Unrestricted). |
| Thinking budget | [ ] | env `MAX_THINKING_TOKENS` per spawn | Third chip: `Think: Off / Low / Med / High` | from Section "Model & inference" below | Per-session override of the global thinking-budget setting. |

Hold off on adding more chips than this — visual budget on the input area
is tight. Move power-user knobs to Settings instead.

---

## Settings — Model & inference

| Setting | Status | Maps to | UI | Default | Notes |
|---|---|---|---|---|---|
| Extended thinking budget | [ ] | env `MAX_THINKING_TOKENS` on spawned `claude` | Dropdown: `Off / Low (1k) / Medium (8k) / High (32k)` | `Off` | Only effective on Opus/Sonnet 4.x. Higher = better reasoning on hard tasks, slower + more $. One-liner — we already pass env per spawn. |
| Append system prompt | [ ] | `--append-system-prompt <text>` per spawn | Multiline text area (~6 rows) | empty | Power feature: persistent "always be terse", "prefer X over Y", project conventions. |
| Max agentic turns | [ ] | `--max-turns <n>` per spawn | Number field, blank = unlimited | blank | Useful for "explain this" chats to prevent runaway tool loops. Less useful for active coding. |

---

## Settings — Tool gating

| Setting | Status | Maps to | UI | Default | Notes |
|---|---|---|---|---|---|
| Disallowed tools | [ ] | `--disallowedTools <name>,…` per spawn | Checkbox list: `Bash / Edit / Write / WebFetch / WebSearch / NotebookEdit` | none disallowed | Lets users build a read-only-ish Claude without `--permission-mode plan` (which is read-only-everything). Useful for "analyze with Read+Grep but never edit my files." |
| Allowed tools (whitelist) | [ ] | `--allowedTools <name>,…` per spawn | Same checkbox list, separate; mutually exclusive with disallow | empty (= no whitelist) | If both set, CLI behavior is allow ∩ ¬disallow. Whitelist is for "exactly these tools, nothing else." Lower priority than disallow. |

---

## Settings — Workspace

| Setting | Status | Maps to | UI | Default | Notes |
|---|---|---|---|---|---|
| Additional working dirs | [ ] | `--add-dir <path>` (repeatable) per spawn | List with add/remove + folder-picker | empty | Big for monorepo sibling-folder workflows ("let Claude see ../shared-lib too"). |
| MCP config path | [ ] | `--mcp-config <file>` per spawn | File picker | empty | Most-asked power-user feature — wires local DB tools, GitHub MCP, etc. Adds support burden (users will report broken MCP servers). |

---

## Explicitly out of scope

- **Temperature / top-p / top-k** — CLI doesn't pass these through. Would
  require bypassing `claude` and calling the raw Anthropic API, losing tool
  use / sessions / MCP / permissions. Not worth the rewrite.
- **System prompt full replacement** (`--system-prompt`, not
  `--append-system-prompt`) — too easy to break Claude Code's built-in
  coding behavior. Append is the safe lever.
- **Streaming JSON parser config / verbose level** — internal debug-only;
  we already have a debug toggle.

---

## Suggested implementation order (smallest first)

1. Thinking budget (Settings + chip) — one env var, very high value for hard tasks
2. Append system prompt (Settings only) — one CLI flag, big power-user win
3. Max turns (Settings only) — one CLI flag, niche but trivial
4. Disallowed tools (Settings only) — checkbox list, high value for cautious users
5. Additional working dirs (Settings only) — list editor, mid value, mid effort
6. MCP config path (Settings only) — file picker, high value, adds support burden
7. Allowed-tools whitelist (Settings only) — lowest priority; users mostly reach for disallow
