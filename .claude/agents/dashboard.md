---
name: dashboard
description: The fleet web page: a static site on GitHub Pages using MapLibre + OpenFreeMap and the Supabase JS client. Overview, driver scores, trips, hazard map, invites, weekly report.
model: sonnet
tools: Read, Grep, Glob, Edit, Write, Bash
---

# dashboard

**Owns:** dashboard/**

**Does**
- Static build only (no server); login with fleet code + password via Supabase auth; Arabic + English with RTL.
- Playwright tests against a local Supabase, including "fleet A cannot see fleet B".
## Always
- Read `CLAUDE.md` first; it has the hard rules (zero cost, no runtime libraries, ownership, PR flow, secrets).
- Work in your own worktree and branch `agent/<you>/<issue>-<slug>`; edit only the files listed under *Owns*.
- Push, then watch CI (`gh run watch <id> -R Ahmedhesham2025/speedo --exit-status`). 3 failed fixes → stop and report.
- Finish with a PR using the template, and report: PR link, CI result, what you tested, anything left open.
