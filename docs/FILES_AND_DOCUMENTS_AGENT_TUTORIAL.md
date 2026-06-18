# Files And Documents Tutorial For Agents And LLMs

This tutorial shows how to use Kira as a local retrieval layer for a multi-level document folder that an agent or LLM can query before answering.

For every MCP service and copy/paste call examples, see [`docs/MCP_KIRA_TOOL_REFERENCES.md`](MCP_KIRA_TOOL_REFERENCES.md).

The example uses a fake local path:

```text
/home/example/knowledge-base
```

Replace it with your real folder path when running the commands.

## 1. What Kira Can Index

Kira indexes these document and knowledge formats by default:

- Markdown: `.md`, `.markdown`
- Plain text: `.txt`
- PDF: `.pdf`
- Word documents: `.docx`
- HTML: `.html`
- YAML and JSON: `.yml`, `.yaml`, `.json`

Excel files such as `.xlsx` are not indexed by default in the current implementation. For spreadsheets, keep the original Excel file in the folder, then export the sheet content to a searchable `.txt`, `.md`, or `.json` companion file.

Kira applies file acceptance rules before parsing. Use `.gitignore` and Kira `accept.exclude` rules to keep drafts, private files, exports, archives, and temporary folders out of the index. Detailed recipes are in:

```text
docs/FILE_EXCLUSION_GUIDE.md
```

## 2. Example Folder Structure

Create a document folder with multiple levels:

```text
/home/example/knowledge-base/
├── company/
│   ├── handbook.pdf
│   ├── vacation-policy.docx
│   └── benefits-summary.md
├── finance/
│   ├── budget-2026.xlsx
│   ├── budget-2026.md
│   └── invoices/
│       ├── vendor-a.pdf
│       └── vendor-b.pdf
├── product/
│   ├── roadmap.docx
│   ├── release-notes.md
│   └── api-overview.json
└── support/
    ├── troubleshooting.html
    ├── refund-policy.txt
    └── faq.md
```

The important detail is `finance/budget-2026.md`. It is the searchable text version of `finance/budget-2026.xlsx`.

Optional `.gitignore` for the document folder:

```gitignore
private/
tmp/
exports/
*.zip
*.log
.env
.DS_Store
```

Example spreadsheet export:

```markdown
# Budget 2026

Source file: budget-2026.xlsx

| Department | Owner | Approved Budget | Notes |
| --- | --- | ---: | --- |
| Engineering | Priya Shah | 420000 | Includes cloud migration |
| Support | Daniel Kim | 180000 | Adds weekend coverage |
| Marketing | Sofia Chen | 250000 | Product launch campaigns |
```

## 3. Start Kira

From the Kira project root:

```bash
cd /home/example/projects/kira
mvn clean package
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.data-dir=/tmp/kira-doc-agent-data
```

Leave this terminal running.

In a second terminal, confirm the service is healthy:

```bash
curl http://localhost:8094/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

## 4. Index The Whole Document Folder

Use a logical repo name such as `office-docs`, `team-knowledge`, or `customer-docs`.

```bash
curl -X POST http://localhost:8094/api/v1/index/full \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "office-docs",
    "repoDir": "/home/example/knowledge-base",
    "gitSha": "local-docs",
    "branch": "main"
  }'
```

Expected result: a JSON response showing how many files and chunks were indexed.

If you change `.gitignore` or Kira acceptance filters later, run this full reindex again so removed files disappear from search results.

## 5. Search The Documents

Ask a question over the full folder:

```bash
curl -X POST http://localhost:8094/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "what is the vacation policy",
    "repo": "office-docs",
    "branch": "main",
    "domain": "KNOWLEDGE",
    "k": 5,
    "mode": "hybrid"
  }'
```

Search inside one folder:

```bash
curl -X POST http://localhost:8094/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "approved engineering budget",
    "repo": "office-docs",
    "branch": "main",
    "domain": "KNOWLEDGE",
    "path": "finance",
    "k": 10,
    "mode": "hybrid"
  }'
```

Search a policy topic:

```bash
curl -X POST http://localhost:8094/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "refund policy and support escalation",
    "repo": "office-docs",
    "branch": "main",
    "domain": "KNOWLEDGE",
    "k": 10,
    "mode": "hybrid"
  }'
```

## 6. Generate Context For An Agent Or LLM

Use `/api/v1/answer-context` when an agent needs compact context instead of raw search JSON:

```bash
curl -X POST http://localhost:8094/api/v1/answer-context \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "summarize the 2026 budget by department and mention the source file",
    "repo": "office-docs",
    "budgetTokens": 1800
  }'
```

The response is plain text context. A simple agent pattern is:

```text
System:
You answer using only the provided Kira context. If the context is missing the answer, say what is missing.

User question:
Summarize the 2026 budget by department.

Kira context:
<paste /api/v1/answer-context response here>
```

## 7. Example Agent Tool Flow

For an agent with tools, use this sequence:

1. Receive user question.
2. Call Kira `/api/v1/search` with `repo`, `domain`, and `k`.
3. If the hits are broad, call `/api/v1/answer-context` with a larger `budgetTokens`.
4. Give the LLM the compact context.
5. Instruct the LLM to cite file paths from the context.
6. If the answer is incomplete, ask a narrower follow-up query.

Example tool request:

```json
{
  "query": "which support policy mentions weekend coverage",
  "repo": "office-docs",
  "branch": "main",
  "domain": "KNOWLEDGE",
  "k": 8,
  "mode": "hybrid"
}
```

Example final-answer instruction for the LLM:

```text
Answer from the retrieved context only. Include the source file path for every claim. Do not guess when the context is incomplete.
```

## 8. Use Kira As An MCP Service

Agents that support MCP can call Kira tools directly instead of calling the REST API with `curl`. You still run one Kira service, index as many repos or document folders as you need, and use the `repo` argument to choose which indexed source to search.

Recommended MCP tools for document and file workflows:

| Tool | Use For |
| --- | --- |
| `search_knowledge(query, repo, branch, k)` | Search documents, Markdown, PDFs, Word docs, HTML, text, YAML, and JSON |
| `semantic_search(query, repo, branch, k)` | Search both code and documents when the domain is unknown |
| `keyword_search(query, repo, domain, k)` | Fast exact-name or identifier search without embedding |
| `answer_context(query, repo, budgetTokens)` | Return compact context for an LLM answer |
| `get_design_for_symbol(fqn)` | Find documents related to a known code symbol |
| `get_code_for_doc(docId)` | Find code related to a document chunk |
| `discover_symbols(partialName, type, k)` | List related code symbols without snippet text |
| `refresh_index(repo, repoDir)` | Reindex a folder from an MCP client after files change |
| `index_status()` | Confirm the MCP server can see the current index |

The search-style MCP tools return `hits` and a nullable `error` field. Check `error` before assuming an empty `hits` list means the folder has no matching documents.

For document-heavy agents, prefer this order:

1. Call `index_status()` once at startup or when debugging.
2. Call `search_knowledge()` with the known `repo` name and a small `k`, usually `3` to `8`.
3. Check `error` before reading `hits`.
4. Call `answer_context()` when the agent needs a compact context block for final reasoning.
5. Use `keyword_search()` for exact filenames, identifiers, or config keys.
6. Use `semantic_search()` only when the question might require both code and documents.

Example MCP calls:

```text
index_status()
```

```text
search_knowledge(
  query="what is the vacation policy",
  repo="office-docs",
  branch="main",
  k=5
)
```

```text
answer_context(
  query="summarize the 2026 budget by department and cite source files",
  repo="office-docs",
  budgetTokens=1800
)
```

Example multi-repo MCP usage:

```text
search_knowledge(
  query="refund policy",
  repo="office-docs",
  branch="main",
  k=5
)
```

```text
search_knowledge(
  query="onboarding checklist",
  repo="hr-docs",
  branch="main",
  k=5
)
```

The same Kira MCP service can answer both calls as long as both folders were indexed into the same data directory.

When files change and the MCP client has access to the same local path, refresh the index without switching to REST:

```text
refresh_index(
  repo="office-docs",
  repoDir="/home/example/knowledge-base"
)
```

Use one running Kira service for multiple repos or document folders when they share a data directory. You do not need a separate Kira process per repo; use separate processes only when you want isolated indexes, isolated JVM resources, or different configuration.

### Stdio MCP Server

Use stdio when the agent starts Kira as a child process.

```json
{
  "mcpServers": {
    "kira": {
      "command": "java",
      "args": [
        "-jar",
        "/home/example/projects/kira/target/ai-retrieval-0.1.0-SNAPSHOT.jar",
        "--spring.ai.mcp.server.stdio=true",
        "--spring.ai.mcp.server.type=SYNC",
        "--kira.data-dir=/tmp/kira-doc-agent-data",
        "--server.port=0"
      ]
    }
  }
}
```

Use the same `kira.data-dir` that you used while indexing, otherwise the MCP server starts with a different empty index.

### HTTP MCP Server

Use HTTP/SSE when you want to start Kira yourself and let one running service be shared by multiple clients.

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.data-dir=/tmp/kira-doc-agent-data \
  --spring.ai.mcp.server.stdio=false \
  --spring.ai.mcp.server.type=ASYNC \
  --server.port=8094
```

Agent MCP SSE URL:

```text
http://localhost:8094/sse
```

Kira is configured with `/mcp/message` as the SSE message endpoint, but MCP clients normally point at the SSE URL. The local project also includes `.mcp.json` as an example MCP client configuration. For more MCP setup details, see:

```text
docs/AGENT_INTEGRATION_GUIDE.md
docs/MCP_ENDPOINT_USAGE.md
```

## 9. Keep Documents Searchable

Use these conventions for better retrieval:

- Give files descriptive names, such as `refund-policy.txt` instead of `doc1.txt`.
- Add short headings inside documents.
- For PDFs made from scans, run OCR before indexing.
- For Excel files, export important sheets to Markdown or JSON.
- Exclude private, generated, archived, and temporary files before indexing.
- Keep one topic per document when possible.
- Put dates or versions in the document body, not only in the filename.
- Reindex after files change.

## 10. Reindex After Updates

For a normal folder without Git history, run full reindex again:

```bash
curl -X POST http://localhost:8094/api/v1/index/full \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "office-docs",
    "repoDir": "/home/example/knowledge-base",
    "gitSha": "local-docs-v2",
    "branch": "main"
  }'
```

If the folder is a Git repository, you can use incremental indexing:

```bash
curl -X POST http://localhost:8094/api/v1/index/incremental \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "office-docs",
    "repoDir": "/home/example/knowledge-base",
    "fromSha": "HEAD~1",
    "toSha": "HEAD",
    "branch": "main"
  }'
```

## 11. Reset The Example

Stop Kira with `Ctrl+C`, then remove the example data:

```bash
rm -rf /tmp/kira-doc-agent-data
```
