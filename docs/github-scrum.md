# How we run Scrum on GitHub Projects

The plan (dates, sprints, scope) is in [scrum.md](scrum.md). This file is the *how*: what to click, what to write, and a full worked example with Sprint 0.

## 1. Scrum to GitHub, at a glance

| Scrum | On GitHub |
|---|---|
| Product backlog | Open issues with no Sprint set, in the **Backlog** view, ordered top to bottom by priority |
| Sprint | A value of the project's **Sprint** field (1 week, Tuesday to Tuesday) |
| Sprint backlog | Issues in the current sprint, on the **Sprint board** view |
| Story + acceptance criteria | One issue with a "Done when" checklist |
| Story points | The **Estimate** field (1, 2, 3, 5, 8) |
| Definition of Done | PR merged into `main` with green CI and 1 approval (enforced by a ruleset) |
| Big deliverables | Milestones **MVP** (Oct 20) and **Hand-in** (Nov 24) |
| Review and retro notes | One "Sprint N" issue per sprint |
| Velocity, burn-up | The project's **Insights** chart |

## 2. One-time setup (do it together at the Sprint 0 planning, about 30 minutes)

### 2.1 Create and link the project (wicked, as repo owner)

1. Click your avatar (top right) → **Your projects** → **New project**.
2. Under *Start from scratch* choose **Board**. Name it `Image Encryption Accelerator` → **Create project**.
3. Go to the repo → **Projects** tab → **Link a project** → pick it.
4. In the project: **⋯** (top right) → **Settings**.
   - **Visibility:** Public (the repo is public, so the teacher can see the process too).
   - **Manage access** → invite Jorge, rusbeko and Alessandro with the **Write** role.

### 2.2 Fields (project **⋯** → **Settings** → **Custom fields**)

- **Status** (exists already): edit the options to `Todo`, `In progress`, `In review`, `Blocked`, `Done`.
- **+ New field** → name `Sprint`, type **Iteration** → starts on **Tue Sep 29, 2026**, duration **1 week** → **Save**. Click **Add iteration** until there are 8, and rename them `Sprint 0` … `Sprint 7`.
- **+ New field** → name `Estimate`, type **Number**.

### 2.3 Views (the tabs at the top of the project)

1. **Sprint board**: rename the default view.
   - Layout: **Board**, column by **Status**.
   - Filter: `sprint:@current`, so the board always shows only this week.
   - Fields on cards (view menu ▾ → **Fields**): Assignees, Labels, Estimate, Linked pull requests.
   - Optional: on the `In progress` column, **⋯** → **Set limit** → 4 (one item per person).
   - Click **Save**.
2. **Backlog**: **+ New view** → **Table**.
   - Filter: `is:open`. **Group by:** Sprint.
   - Columns: Title, Assignees, Labels, Estimate, Milestone, Status.
   - In the group menu turn on **Field sum → Estimate**, so every sprint shows its total points.
   - The **No Sprint** group *is* the product backlog. Drag rows to order it; drag a row into another group to put it in that sprint.
3. **Roadmap** (optional): **+ New view** → **Roadmap**, dates from **Sprint**. Good for the presentation.

### 2.4 Automations (project **⋯** → **Workflows**)

| Workflow | Setting |
|---|---|
| Auto-add to project | On, repo `image-encryption-accelerator`, filter `is:issue` |
| Item added to project | On → Status `Todo` |
| Item closed | On → Status `Done` (default) |
| Pull request merged | On → Status `Done` (default) |

With these, nobody has to add issues to the board or move merged work to Done by hand.

### 2.5 Repo setup (repo → **Issues** tab)

- **Milestones** → **New milestone**:
  - `MVP`, due Oct 20, 2026: "Simulation-only Ascon encryption of the grayscale image".
  - `Hand-in`, due Nov 24, 2026.
- **Labels** → **New label**: `hardware` (Chisel), `software` (Scala reference, test harness), `infra` (CI, build, repo), `spike` (research with a written output), `sprint` (review and retro issues). Keep the existing `documentation` and `bug`.

### 2.6 Protect `main` (wicked only, after CI has run once, see issue #3 below)

Repo **Settings** → **Rules** → **Rulesets** → **New ruleset** → **New branch ruleset**:

- Name `main`, Enforcement **Active**, Target branches → **Add target** → **Include default branch**.
- Keep **Restrict deletions** and **Block force pushes**.
- **Require a pull request before merging** → Required approvals: **1**.
- **Require status checks to pass** → add `test` (the CI job).
- **Create**.

From then on the Definition of Done is enforced: nothing reaches `main` without a review and green tests.

## 3. How to write an issue (a story)

- **Title:** what it does, imperative: "Run sbt test in CI on every push and PR".
- **Body:**

```markdown
## Why
One or two sentences: what this unlocks.

## What
The change, briefly. Files or modules it touches.

## Done when
- [ ] Concrete check 1 (a test, a file, a visible result)
- [ ] Concrete check 2

## Notes
Links, dependencies ("blocked by #2"), open questions.
```

- **Fields:** Sprint (empty while it is in the backlog), Estimate, Assignee (one owner), Milestone, one or two labels.
- **Size:** anything above 5 points is too big for one person in one week. Split it into sub-issues (**Create sub-issue** at the bottom of the issue).

## 4. The weekly routine

| When | What | Where on GitHub |
|---|---|---|
| Tuesday, 1 hour | Review and retro of the sprint that ends, then planning of the next one | Sprint board, Backlog view, the "Sprint N" issue |
| During the week | Pick a card, branch, PR, review, merge | Sprint board, PRs |
| 2 to 3 times a week | Async standup in the group chat, always with issue numbers | Sprint board open next to the chat |
| Any time | Blocked: move the card to `Blocked`, comment why and @mention who can unblock it | The issue |

Rules:

- No issue, no branch. Every piece of work is on the board.
- One issue → one branch → one PR, and the PR description says `Closes #N`.
- One card `In progress` per person at a time.
- The sprint never gets longer. What isn't done on Tuesday moves to the next sprint.

## 5. Worked example: Sprint 0 (Tue Sep 29 → Tue Oct 6)

**Sprint goal:** the team can work in Scrum. The board, CI and a protected `main` are live, and Sprint 1 is planned.

### 5.1 Planning (today, Sep 29)

Do the setup of section 2 together, then create these issues. The numbers assume you create them in this order on the empty repo.

| # | Title | Labels | Est. | Milestone | Owner |
|---|---|---|---|---|---|
| 1 | Sprint 0: goal, review and retro | `sprint` | – | – | whoever runs the meeting |
| 2 | Run `sbt test` in CI on every push and PR | `infra` | 2 | MVP | pick at planning |
| 3 | Protect `main` with a ruleset (PR, 1 review, green CI) | `infra` | 1 | MVP | wicked (needs admin) |
| 4 | Add a story issue template | `infra`, `documentation` | 1 | MVP | pick at planning |
| 5 | Spike: Ascon-AEAD128 notes and NIST test vectors | `spike`, `software` | 3 | MVP | pick at planning |
| 6 | Write the Sprint 1 and 2 backlog as issues | `documentation` | 2 | MVP | pick at planning |

Total: 9 points. For each issue, set **Sprint = Sprint 0**, the estimate and the owner. Issue #1 gets the goal as its first line.

What each one delivers:

- **#3** is blocked by #2 (the ruleset needs the CI check to exist). Put it in `Blocked` with a comment "waiting for #2" until #2 is merged.
- **#4** adds `.github/ISSUE_TEMPLATE/story.md` with the body from section 3, so **New issue** offers it.
- **#5** produces `docs/ascon-notes.md` (Ascon-AEAD128 from NIST SP 800-232, how the image maps to 16-byte blocks, which key and nonce the MVP uses) and the official test vectors in `src/test/resources/`. Sprint 1's software reference and permutation stories depend on it.
- **#6** turns the Sprint 1 and Sprint 2 lists in [scrum.md](scrum.md) into issues with "Done when" checklists and estimates, with Sprint left empty. They land in the backlog, ready for the next planning.

### 5.2 One card's journey: issue #2 (CI)

The owner:

1. Drags #2 from `Todo` to `In progress`.
2. On the issue page, right sidebar → **Development** → **Create a branch** → keep the suggested name → **Create branch**, then check it out:

   ```bash
   git fetch origin
   git checkout 2-run-sbt-test-in-ci-on-every-push-and-pr
   ```

3. Adds `.github/workflows/ci.yml`:

   ```yaml
   name: CI
   on:
     push:
       branches: [main]
     pull_request:
   jobs:
     test:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v5
         - uses: actions/setup-java@v5
           with:
             distribution: temurin
             java-version: 21
             cache: sbt
         - uses: sbt/setup-sbt@v1
         - run: sbt test
   ```

   Java 21 because the build uses sbt 2, which needs Java 17 or newer.
4. Commits, pushes and opens a PR titled "Run sbt test in CI" with `Closes #2` in the description, and requests a review from a teammate.
5. Moves the card to `In review`. The PR's **Checks** tab shows the new CI running on the PR itself.

The reviewer reads the diff, waits for the green check and clicks **Approve**. The owner clicks **Squash and merge** and deletes the branch. #2 closes automatically and the workflow moves the card to `Done`.

Then wicked moves #3 from `Blocked` to `In progress` and creates the ruleset from section 2.6, picking the `test` check.

### 5.3 A standup message in the chat

```
Done: #2 merged, CI is green on main
Next: #4
Blocked: nothing
```

### 5.4 Review and retro (Tue Oct 6)

1. Open the **Sprint board**. Everyone shows what's in `Done` (the demo for Sprint 0 is a PR with the green check, and a direct push to `main` being refused).
2. Anything not in `Done` gets **Sprint = Sprint 1**.
3. Post a comment on #1 and close it:

   ```markdown
   ## Review
   Goal met: yes / partly
   Done: #2, #3, #4, #6 (6 points)
   Moved to Sprint 1: #5 (3 points)

   ## Retro
   Keep: ...
   Change: ...
   ```

4. Open **Insights** (the chart icon at the top of the project). The burn-up chart filtered to `sprint:"Sprint 0"` shows the week. Screenshot it for the final presentation.
5. Start Sprint 1 planning right away. Create "Sprint 1: goal, review and retro", then in the **Backlog** view drag issues from **No Sprint** into **Sprint 1**. Stop when the Estimate sum is about what you finished in Sprint 0.
