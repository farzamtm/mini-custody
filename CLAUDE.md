# CLAUDE.md

Guidance for Claude Code in this repository. The full working agreement lives in
[AGENTS.md](AGENTS.md) so that every agent reads the same rules — that file is the
source of truth, and it is imported here:

@AGENTS.md

## The short version

1. **Always work on a feature branch.** Never commit to `main`. Branch off an
   up-to-date `main` (`m<n>-<slug>` for a milestone, `ci/<slug>` for build work,
   `fix/<slug>` for a bug fix) and land through a pull request.
2. **Check the pipeline before pushing.** `./gradlew clean check` must be green
   locally — it runs exactly what CI runs — and after pushing, watch the run with
   `gh pr checks --watch` until the single `Gate` check passes.
3. **Open every pull request with a human-readable section.** One or two sentences
   of prose at the top saying what the change does and what it closes, before any
   table, checklist or detail. The structure and a worked example are in AGENTS.md;
   PRs #2–#6 are the house style.

Do not push, open a pull request, or merge unless asked.
