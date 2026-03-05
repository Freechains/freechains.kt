Here's the plan for this session:

## Session Plan: freechains.kt — Command-Line Build & CI

### Goal
Migrate the freechains.kt project from IntelliJ-only workflows
to full command-line tooling, in 3 steps:

### Step 1 — Compile from command line
- Clone the repo into our working directory
- Verify `./gradlew build` works as-is
- If not, fix `build.gradle` (e.g., fat JAR task, dependency
  issues, Gradle version compatibility)
- Ensure `freechains.main.jar` is produced

### Step 2 — Run tests from command line
- Verify `./gradlew test` works
- If not, fix test configuration (JUnit 5 platform, classpath,
  etc.)
- Document the exact command

### Step 3 — GitHub Actions CI
- Create `.github/workflows/ci.yml`
- Workflow triggers: push & PR to `main`
- Steps: checkout → JDK setup → Gradle build → Gradle test
- Add CI badge to README

### Step 4 — Update README
- Replace IntelliJ-based instructions with command-line
  equivalents
- Document: build, test, install

---

### Ground Rules (from CLAUDE.md)
- I stay in **plan mode** — no file edits without your
  authorization
- Before each edit, I'll make a final suggestion
- After edits, I'll summarize changes and return to plan mode
- I won't run tests — you'll do that by hand

Shall I start by cloning the repo and verifying what currently
works?
