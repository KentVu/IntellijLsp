<!--
Sync Impact Report:
- Version change: 0.0.0 → 1.0.0 (initial ratification)
- Added: 5 core principles, 2 additional sections
- Templates: constitution.md (✅ filled)
- Pending: spec.md, plan.md, tasks.md migrations
-->

# IntelliJ LSP Server Constitution

## Core Principles

### I. Platform Integration First

**Description**: All LSP features MUST prioritize native IntelliJ Platform SDK capabilities (PSI, indexing, navigation, refactoring) over custom language-specific implementations.

**Rationale**: This ensures consistency and correctness across languages and frameworks, reduces maintenance overhead, and benefits from IntelliJ's ongoing improvements.

### II. Protocol Conformance

**Description**: The LSP protocol implementation MUST strictly follow the [LSP 3.17 specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/). The input and output models of all handlers MUST match the specification.

**Rationale**: This ensures compatibility with popular clients (Neovim, Emacs, VS Code) and reduces client configuration complexity.

### III. Project Isolation

**Description**: Each open IntelliJ project MUST run an independent LSP server instance (with its own port/socket and document state). Requests across projects MUST NOT interfere with one another.

**Rationale**: This supports parallel development across multiple projects and prevents state contamination and resource conflicts.

### IV. Graceful Degradation

**Description**: When an LSP feature cannot retrieve complete data, the server SHOULD return partial or empty results rather than an error. All handlers MUST catch and log exceptions and MUST NOT cause the server to crash.

**Rationale**: This improves client stability and makes issues easier to diagnose.

### V. Observability

**Description**: All core operations MUST emit structured logs (including request ID, duration, and result status). Server state changes MUST be reflected in real time through the status bar widget.

**Rationale**: This simplifies debugging and troubleshooting and improves the developer experience.

## Technical Constraints

- **Transport**: Supports only TCP (localhost) and Unix domain sockets; MUST ensure local-only access as a security boundary.
- **Threading model**: Network I/O uses Kotlin coroutines; PSI access MUST run inside a ReadAction.
- **Dependencies**: MUST use IntelliJ's bundled Gson/Jackson and avoid adding external JSON libraries.

## Development Workflow

- **Feature development**: Follow the speckit workflow (spec → plan → tasks → implement → verify).
- **Code review**: All PRs MUST include references to the relevant spec/tasks.
- **Release**: Build with `./gradlew buildPlugin`; versions follow MAJOR.MINOR.PATCH.

## Governance

- This Constitution takes precedence over all other practices.
- Revisions must document the changes, increment the version, and update `LAST_AMENDED_DATE`.
- See `README.md` for runtime development guidance.

**Version**: 1.0.0 | **Ratified**: 2025-12-14 | **Last Amended**: 2025-12-14
