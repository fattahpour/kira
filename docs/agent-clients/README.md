# Agent Client Configuration Guide

This folder explains how to configure coding agents to work with this Kira repo and with the Kira MCP server.

Use these guides when you want to add project instructions, connect MCP, or teach an agent to search Kira before reading large parts of the codebase.

## Which Guide To Use

| Client | Instruction File | Config File | Guide |
| --- | --- | --- | --- |
| Codex | `AGENTS.md`, plus optional `~/.codex/AGENTS.md` | `~/.codex/config.toml` or trusted project `.codex/config.toml` | [codex.md](codex.md) |
| Claude Code | `CLAUDE.md`, plus optional `.claude/` files | `.mcp.json` or `.claude/settings.json` depending on client setup | [claude.md](claude.md) |
| Gemini CLI | `GEMINI.md`, plus optional `~/.gemini/GEMINI.md` | `.gemini/settings.json` or `~/.gemini/settings.json` | [gemini.md](gemini.md) |
| GitHub Copilot | `.github/copilot-instructions.md`, plus optional `.github/instructions/*.instructions.md` | VS Code MCP server settings or workspace MCP config | [copilot.md](copilot.md) |
| OpenCode | `AGENTS.md` and files listed in `opencode.json` `instructions` | `opencode.json` or `~/.config/opencode/opencode.json` | [opencode.md](opencode.md) |

## Recommended Kira-First Workflow

Tell every agent this:

1. Use Kira search tools before broad file reads.
2. Use exact-name tools first when possible: `keyword_search`, `discover_symbols`, `find_symbol`, then `get_symbol`.
3. Use `answer_context` for one final compact context block.
4. Read full files only after Kira identifies the relevant path or symbol.
5. After changing code or docs, reindex with REST or MCP before asking Kira about the new content.

Use [shared-instructions-template.md](shared-instructions-template.md) as the base content for client instruction files.

For all Kira MCP services, parameters, return shapes, and call examples, use:

```text
../MCP_KIRA_TOOL_REFERENCES.md
```

## Current Repo Files

This repo already contains:

- `.mcp.json` with `kira-stdio` and `kira-http` server examples.
- `CLAUDE.md` with project instructions for Claude Code.
- `GEMINI.md`, currently available for Gemini CLI project context.
- `.github/copilot-instructions.md` with Kira-first instructions for GitHub Copilot.

This repo does not currently include:

- `AGENTS.md` for Codex/OpenCode shared project instructions.
- `opencode.json` for OpenCode project configuration.
- `.gemini/settings.json` for Gemini project MCP settings.
- `.codex/config.toml` for project-scoped Codex MCP settings.

Add those files only when you want that client to load project-local configuration automatically.

## References

- OpenAI Codex `AGENTS.md`: https://developers.openai.com/codex/guides/agents-md
- OpenAI Codex config reference: https://developers.openai.com/codex/config-reference
- Claude Code memory: https://code.claude.com/docs/en/memory
- Claude Code `.claude` directory: https://code.claude.com/docs/en/claude-directory
- Gemini CLI `GEMINI.md`: https://google-gemini.github.io/gemini-cli/docs/cli/gemini-md.html
- Gemini CLI MCP: https://google-gemini.github.io/gemini-cli/docs/tools/mcp-server.html
- OpenCode config: https://opencode.ai/docs/config/
- OpenCode rules: https://opencode.ai/docs/rules/
- OpenCode MCP servers: https://opencode.ai/docs/mcp-servers/
- GitHub Copilot custom instructions: https://docs.github.com/copilot/customizing-copilot/adding-custom-instructions-for-github-copilot
- VS Code Copilot custom instructions: https://code.visualstudio.com/docs/copilot/customization/custom-instructions
- VS Code MCP servers: https://code.visualstudio.com/docs/agent-customization/mcp-servers
