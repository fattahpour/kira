# Kira File Exclusion Guide

This guide explains how to keep unnecessary, generated, sensitive, or noisy files out of Kira's index.

After indexing, agents can query the accepted files through the MCP tools listed in [`docs/MCP_KIRA_TOOL_REFERENCES.md`](MCP_KIRA_TOOL_REFERENCES.md).

Kira decides whether a file is indexed in this order:

1. `.gitignore` and `.git/info/exclude` are applied when `kira.respect-gitignore` is enabled.
2. Kira `accept.include` rules decide which remaining files are eligible.
3. Kira `accept.exclude` rules remove files from that eligible set.
4. The source classifier decides how to parse accepted files.

## 1. Use `.gitignore` First

Kira respects `.gitignore` by default:

```yaml
kira:
  respect-gitignore: true
```

Put normal build outputs, local environment files, and dependency folders in `.gitignore`:

```gitignore
target/
build/
out/
node_modules/
.env
.env.*
*.log
*.class
.DS_Store
```

This is the best first layer because it also protects other tools from seeing untracked local junk.

Important: `.gitignore` does not hide files that are already tracked by Git. If a tracked file should not be indexed by Kira, add it to Kira's `accept.exclude` list too.

For this Kira repo, the default config already excludes common build and IDE paths. Add project-specific rules only when a folder is noisy for retrieval, sensitive, generated, or not useful for the agent.

## 2. Configure Global Kira Filters

Global filters live in `src/main/resources/application.yml` under `kira.accept`.

Example:

```yaml
kira:
  respect-gitignore: true

  accept:
    include:
      - "**/*.java"
      - "**/*.md"
      - "**/*.markdown"
      - "**/*.yml"
      - "**/*.yaml"
      - "**/*.json"
      - "**/*.pdf"
      - "**/*.docx"
      - "**/*.html"
      - "**/*.txt"

    exclude:
      - "**/target/**"
      - "target/**"
      - "**/build/**"
      - "build/**"
      - "**/out/**"
      - "out/**"
      - "**/.git/**"
      - ".git/**"
      - "**/.idea/**"
      - "**/.vscode/**"
      - "**/node_modules/**"
      - "node_modules/**"
      - "**/*.class"
      - "**/*.jar"
      - "**/*.war"
      - "**/*.zip"
      - "**/*.tar"
      - "**/*.gz"
      - "**/*.log"
      - "**/.env"
      - "**/.env.*"
      - "**/.DS_Store"
```

Use `include` to define what Kira is allowed to index. Use `exclude` to remove known-noisy files even if they match an include rule.

## 3. Configure Per-Repo Filters

Use per-repo filters when one indexed repo needs different rules from the global default.

```yaml
kira:
  repos:
    - id: my-service
      path: /path/to/my-service
      branches:
        mode: SINGLE
        tracked:
          - main
      auto-sync:
        enabled: true
        interval-seconds: 300
      accept:
        include:
          - "src/**/*.java"
          - "docs/**/*.md"
          - "api/**/*.yaml"
          - "README.md"
        exclude:
          - "src/test/**"
          - "**/target/**"
          - "**/build/**"
          - "**/*.log"
          - "**/.env*"
```

This is useful when you want Kira to index production code and docs, but skip tests, generated clients, vendored code, or local files.

For the current Kira project, a focused repo config can look like this:

```yaml
kira:
  repos:
    - id: kira
      path: /home/example/projects/kira
      branches:
        mode: SINGLE
        tracked:
          - main
      accept:
        include:
          - "src/**/*.java"
          - "docs/**/*.md"
          - "README.md"
          - "pom.xml"
          - "src/main/resources/**/*.yml"
        exclude:
          - "target/**"
          - "docs/superpowers/**"
          - "src/test/**"
          - "**/*.log"
          - "**/.env*"
```

Use `docs/superpowers/**` only if you want the agent to ignore planning and implementation history. Remove that rule when you want Kira to search those historical docs too.

## 4. Common Exclusion Recipes

Skip build output:

```yaml
exclude:
  - "**/target/**"
  - "**/build/**"
  - "**/out/**"
```

Skip dependencies and vendored files:

```yaml
exclude:
  - "**/node_modules/**"
  - "**/vendor/**"
  - "**/.mvn/wrapper/**"
```

Skip IDE and OS files:

```yaml
exclude:
  - "**/.idea/**"
  - "**/.vscode/**"
  - "**/.DS_Store"
```

Skip secrets and local environment files:

```yaml
exclude:
  - "**/.env"
  - "**/.env.*"
  - "**/*secret*"
  - "**/*credential*"
```

Skip generated API clients:

```yaml
exclude:
  - "**/generated/**"
  - "**/openapi-generated/**"
  - "**/target/generated-sources/**"
```

Skip tests when you only want production implementation:

```yaml
exclude:
  - "**/src/test/**"
  - "**/*Test.java"
  - "**/*Tests.java"
```

## 5. Reindex After Changing Filters

Filter changes affect future indexing. After changing `.gitignore` or `application.yml`, restart Kira and reindex the repo.

Full reindex:

```bash
curl -X POST http://localhost:8094/api/v1/index/full \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "my-service",
    "repoDir": "/path/to/my-service",
    "gitSha": "local",
    "branch": "main"
  }'
```

If the repo is configured under `kira.repos`, you can trigger configured sync:

```bash
curl -X POST http://localhost:8094/api/v1/index/sync/my-service
```

## 6. Verify The Result

Check index status:

```bash
curl http://localhost:8094/api/v1/index/status
```

Search for a file or folder you expect to be excluded:

```bash
curl -X POST http://localhost:8094/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "target node_modules .env",
    "repo": "my-service",
    "branch": "main",
    "k": 10
  }'
```

If excluded files still appear, check these points:

- The file may already be tracked by Git, so `.gitignore` alone will not hide it.
- The path pattern may not match the file's repo-relative path.
- Kira may be running with a different config or `kira.data-dir`.
- The repo may need a full reindex after changing filters.

## 7. Recommended Default For Most Projects

For most projects, use `.gitignore` plus this Kira filter:

```yaml
kira:
  respect-gitignore: true
  accept:
    include:
      - "**/*.java"
      - "**/*.md"
      - "**/*.yaml"
      - "**/*.yml"
      - "**/*.json"
      - "**/*.txt"
    exclude:
      - "**/target/**"
      - "**/build/**"
      - "**/out/**"
      - "**/node_modules/**"
      - "**/.git/**"
      - "**/.idea/**"
      - "**/.vscode/**"
      - "**/*.class"
      - "**/*.jar"
      - "**/*.log"
      - "**/.env*"
```
