# AI Development Journal — Incident RCA Assistant

A running record of the AI-assisted build of this app: what was asked, what
was ambiguous, what was decided, and what was verified. Entries are in
chronological order.

## 1. Spec review

Read `SPEC.md` and summarized the requirements before writing any code:

- CLI Java app that investigates a production incident by gathering context
  from documented sources (spec, past resolutions, ticket history, code) and
  produces a structured RCA — never taking action on real systems, never
  making an authenticated network call (FR-1–FR-9, Non-Goals in Section 3).
- Output must always contain, in order: Summary, Sources consulted,
  Hypotheses (2+, each with for/against evidence), Recommended next steps
  (verification first), Confidence level with reasoning (Section 7).
- Data folder defaults to `./data/`, overridable via an optional CLI arg
  (FR-9/AC-9), reading 5 files described in Section 6.

**Ambiguities flagged before implementing:**
1. AC-9 says the folder arg affects "3 source files," but Section 6 lists 4
   source-type files plus the incident ticket (5 total) — a wording
   inconsistency.
2. How the FR-1 incident description is actually supplied (CLI flags vs. a
   file) wasn't stated outright.
3. Whether the RCA reasoning should be LLM-backed or fully deterministic —
   the spec's Non-Goals only ban authenticated calls to JIRA/SharePoint/
   FreshService, and are silent on calling an LLM.
4. None of the 5 mock data files existed yet — someone had to author them.
5. No build tooling or Java version was specified.

## 2. Decisions made (via clarifying questions)

| Decision | Answer |
|---|---|
| Reasoning engine | Deterministic, rule-based Java — no LLM call, no network calls of any kind. |
| Incident input | Read `incident-ticket.txt` from the data folder (not CLI flags). |
| Folder-arg scope (resolves AC-9 ambiguity) | All 5 files live in the same folder and move together with `--data-folder`; treated the literal "3" in AC-9 as a minor wording slip. |
| Mock data authoring | User supplies the files, not the assistant. |
| Build tooling | Maven, Java 11 release target. |
| Output destination | stdout **and** written to a file. |

## 3. Mock data

The user pasted 3 files (`Mock-tech-spec.txt`, `Mock-jira-export.txt`,
`Mock-past-resolution-notes.txt`) describing a real scenario, then later
pasted `PaymentCalculator.java.txt` and `Mock-incident-ticket.txt`. All 5
were renamed to the spec's exact required filenames (`tech-spec.txt`,
`jira-export.txt`, `past-resolution-notes.txt`, `PaymentCalculator.java.txt`,
`incident-ticket.txt`), per the user's confirmation.

**The scenario (as established by the pasted files):** `PaymentCalculator`
must round final claim payments using `RoundingMode.HALF_UP` per a
regulatory requirement (tech-spec.txt Section 2), after an earlier incident
(JIRA-3987) where it silently defaulted to `HALF_EVEN`. A later "readability
refactor" (JIRA-4521, 3 days before the incident) consolidated the rounding
logic into `roundAmount()` but switched it back to `HALF_EVEN`, reasoning
that it "avoids statistical bias" — without recognizing this violated the
regulatory contract and without functional test changes to catch it. This
produced the incoming ticket (FS-88213): claim payments off by 1-2 cents on
surcharge claims.

One gap was found and closed: `incident-ticket.txt` (a realistic
FreshService-style ticket) never explicitly stated the "affected component"
field required by FR-1/AC-1. Per the user's choice, a single explicit line
(`Affected Component: PaymentCalculator (claim payment rounding/surcharge
calculation)`) was added, leaving the rest of the pasted ticket untouched.

## 4. Implementation

Built as a dependency-free Maven project:

- `IncidentTicket` — alias-based label parser (recognizes `Symptom`/
  `Description`, `Component`/`Affected Component`, `Timestamp`/
  `First observed`, etc.) so it can read either a plainly-labeled ticket or a
  realistic helpdesk-style one. Fails fast (clear stderr message, exit 1, no
  interactive prompt) if a required field is missing.
- `JiraTicket` — parses the fixed-width ticket table and `--- ID detail ---`
  blocks; tolerant of a non-matching layout (falls back to generic
  keyword matching over the raw text rather than crashing).
- `RcaEngine` — the core: extracts the spec's mandated rounding mode and the
  code's actual rounding mode via regex, cross-references the incident's own
  ticket ID against the Jira export, identifies the regression ticket vs. the
  prior fix vs. a plausible-but-irrelevant "recent change" (the caching
  ticket), and assembles 3 hypotheses with real (not templated) evidence
  pulled from the files at runtime. Confidence is scored from how many
  distinct source types corroborate the leading hypothesis.
- `ReportBuilder` — renders the 5 required sections in order.
- `Main` — reads the data folder (default `./data/`, or `args[0]`), prints
  the report to stdout, and writes a copy to `rca-report.txt`.

## 5. Bug found and fixed during verification

Initial run misidentified **JIRA-4519** (the unrelated policy-lookup caching
ticket) as the rounding regression, instead of JIRA-4521. Cause: JIRA-4519's
own detail text contains the disclaiming sentence "Does not touch payment
calculation or **rounding logic**" — a naive substring match on "round" read
that disclaimer as positive evidence. Fixed by adding a
`disclaimsRelevance()` check (looks for phrases like "does not touch",
"unrelated to", "does not affect") that excludes a ticket from a topic match
when its own text explicitly disclaims it. Re-verified the fix correctly
attributes the regression to JIRA-4521.

## 6. Verification against acceptance criteria

- **AC-1**: runs to completion with no prompts when all 5 fields are
  present; confirmed separately that it exits with code 1 and a clear stderr
  message (no interactive prompt) when a required field is missing.
- **AC-2/AC-3/AC-5**: the real run cites all 4 source types (exceeds the
  "at least 3" bar); all 3 hypotheses name specific source files and carry
  both a "for" and an "against" (or explicit "None found").
- **AC-4**: tested in a scratch folder with one empty file, one irrelevant
  file, and one missing file — each produced its own explicit "nothing
  relevant found" sentence; confidence correctly dropped to Low as
  corroboration weakened.
- **AC-6/AC-7/AC-8**: next steps always lead with a verification/
  reproduction step, never a direct fix; output always ends with a
  High/Medium/Low level plus reasoning; no sentence anywhere claims the app
  itself performed a real-world action (next steps explicitly note fixes
  "require... deployment through normal change management, which this tool
  does not perform").
- **AC-9**: tested with a custom folder path argument (a scratch copy of the
  data files) — confirmed it reads from that path via plain file I/O only,
  no network calls.

## 7. Current state

- Real run concludes **High confidence**: JIRA-4521's rounding refactor
  reintroduced the `HALF_EVEN` defect previously fixed in JIRA-3987.
- `target/` and `rca-report.txt` added to `.gitignore` (build/run artifacts,
  not source).
- To run: `mvn clean package` then `java -jar target/incident-rca-assistant.jar`
  (optionally passing a data-folder path as the first argument).

## 8. Second way to access files: a OneDrive-synced folder (stand-in for SharePoint)

FR-9's data-folder argument is generic — it accepts any local path, read via
plain file I/O, so the same mechanism that points at the bundled `data/`
folder can instead point at a OneDrive-synced folder standing in for a
SharePoint/JIRA/FreshService location, with no network or auth call
involved (per Section 3's Non-Goals).

Verified this against a real OneDrive folder the user provided:
`C:\Users\bhavsarpooja.bhupe\OneDrive - HCL TECHNOLOGIES LIMITED\Desktop\Amerisure\MOCK-RCA-Lab-DELETE-AFTER`.
It already contained all 5 correctly-named files, including the
`Affected Component` line already present in its `incident-ticket.txt`.

To make which folder was actually used directly verifiable (rather than
something to infer from output differences), added a startup line in `Main`
that prints the resolved absolute data-folder path to stderr on every run:
`(Reading source files from: <absolute path>)`. Confirmed it correctly shows
the project's `data/` folder by default and the OneDrive folder path when
passed explicitly.

Run command:
```
java -jar target\incident-rca-assistant.jar "C:\Users\bhavsarpooja.bhupe\OneDrive - HCL TECHNOLOGIES LIMITED\Desktop\Amerisure\MOCK-RCA-Lab-DELETE-AFTER"
```
