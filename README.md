# Claude Code for IntelliJ

An IntelliJ IDEA plugin that integrates [Claude Code CLI](https://github.com/anthropics/claude-code) directly into your IDE. Chat with Claude in a side panel, let it read, edit, and create files in your project — all without leaving your editor.

## Features

### Chat Sessions
- Side panel with multiple parallel Claude Code sessions
- Project-aware: Claude works in your open project's directory
- Session memory: Claude remembers context across messages
- Markdown rendering with syntax-highlighted code blocks
- Tool-use indicators with colored status signals and diff summaries
- Clickable file links for every file Claude creates or modifies
- Changed files summary after each response, grouped by folder
- Git diff viewer for modified files (one-click `[diff]` link)
- Copy and re-edit buttons on messages and code blocks
- Clickable web links open in your default browser
- Inline permission prompts (Allow/Deny) — no modal dialogs

### Right-Click Context Menu
- **Explain with Claude** — understand code, methods, or entire files
- **Refactor with Claude** — get suggestions for readability, performance, and best practices
- **Generate Tests with Claude** — create unit tests for selected code or whole files
- **Fix with Claude** — identify and fix bugs in selected code
- **Send to Claude** — send a file or selected text as context to the active session

Works from both the editor (selected code or full file) and the project tree (file-level actions).

### Keyboard Shortcuts
| Shortcut | Action |
|----------|--------|
| `Cmd+Shift+I` / `Ctrl+Shift+I` | Toggle Claude Code panel |
| `Enter` | Send message |
| `Shift+Enter` | New line |
| `Escape` | Stop current request |
| `Cmd+C` / `Ctrl+C` (×2) | Stop current request |

### Settings
Configurable via **Settings → Tools → Claude Code**:
- Claude CLI path
- Model selection (Opus, Sonnet, Haiku)
- Font size
- Max concurrent sessions
- Auto-accept file changes or require manual approval
- Send selected code as context (toggle)
- Experimental code completion (opt-in)

## Prerequisites

1. **Install the Claude CLI:**
   ```bash
   npm install -g @anthropic-ai/claude-code
   ```

2. **Authenticate:**
   ```bash
   claude
   ```
   Follow the prompts to sign in.

## Installation

### From disk
1. Download the latest release zip from [Releases](../../releases)
2. In IntelliJ: **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. Select the downloaded `.zip` file
4. Restart the IDE

### Build from source
```bash
git clone <repo-url>
cd claude-code-intellij
./gradlew buildPlugin
```
The plugin zip will be at `build/distributions/claude-code-intellij-0.1.0.zip`.

## Platform Support

| Platform | Status |
|----------|--------|
| macOS | ✅ Supported |
| Linux | ✅ Supported |
| Windows | ❌ Not supported |

**IDE compatibility:** IntelliJ IDEA 2024.1+ (build 241–252.*)

## How It Works

The plugin spawns the `claude` CLI process with `--output-format stream-json` and parses the streaming JSON output in real time. A pseudo-TTY is allocated via `script` to prevent Node.js stdout buffering.

Each chat session runs as an independent CLI process with session resumption support (`--resume`), so context is preserved across messages within a session.

## License

MIT
