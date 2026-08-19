# SPEC.md — Incident RCA Assistant

## 1. Overview

A command-line Java application that investigates a production incident by
gathering context from multiple documented sources and producing a
structured root-cause analysis (RCA), following the same investigation
discipline a human support engineer would use — check the spec, check
history, check recent changes, check the code — before concluding anything.

## 2. Goals

- Turn a raw incident description into a structured, evidence-backed RCA.
- Force the app to say what it checked and what it found (or didn't find)
  in each source, rather than producing an unattributed answer.
- Make the app's reasoning checkable against acceptance criteria below.

## 3. Non-Goals

- This app does not take any action on real systems (no ticket edits, no
  code commits, no deployments). It only investigates and recommends.
- This app does not make any authenticated network call to JIRA,
  SharePoint, or FreshService in this version (no Microsoft Graph API, no
  OAuth). It only ever reads plain files from a local folder path — see
  FR-9. Whether that folder happens to be OneDrive-synced is irrelevant to
  the app; it has no awareness of where the folder's contents actually
  come from. True live API integration (the app authenticating to these
  services itself) is a future phase — see Section 9.

## 4. Actors

- **Engineer** — runs the app with an incident description, reads the
  output, decides what to actually do.

## 5. Functional Requirements

| ID | Requirement |
|----|-------------|
| FR-1 | The app must accept an incident description (symptom, error/log if any, affected component, timestamp, environment) as input. |
| FR-2 | The app must gather context from these source types, in this order: (a) technical spec / requirements, (b) past resolution notes for similar incidents, (c) recent change history (tickets), (d) current code, if available. |
| FR-3 | The app's output must state which sources it used, by name, for each conclusion it draws. |
| FR-4 | If a source has nothing relevant, the app must say so explicitly rather than omitting it silently. |
| FR-5 | The app must produce at least 2 hypotheses for the root cause, each with evidence for and evidence against. |
| FR-6 | The app must produce recommended next steps that include a verification step before any fix is applied. |
| FR-7 | The app must state a confidence level (High / Medium / Low) with a reason. |
| FR-8 | The app must never claim to have taken an action on a real system (e.g. "I've reverted the code" or "I've closed the ticket"). |
| FR-9 | The app must accept an optional command-line argument for the data folder path, defaulting to `./data/` if not provided. This lets the same file-reading code point at a OneDrive-synced folder without the app performing any network or auth operation itself. |

## 6. Data Sources (this version — mock)

Located in `./data/`:

| File | Stands in for |
|------|----------------|
| `tech-spec.txt` | A technical spec / requirements document (e.g. from SharePoint) |
| `jira-export.txt` | Ticket history (e.g. from JIRA) |
| `past-resolution-notes.txt` | Notes from a previously resolved, similar incident |
| `PaymentCalculator.java.txt` | The current code for the affected component |
| `incident-ticket.txt` | The incoming incident report (this is the FR-1 input, not a searched source) |

## 7. Output Format

The app's output must contain these sections, in this order:

1. **Summary** — 1-2 sentences.
2. **Sources consulted** — named, with what each contributed (or "nothing
   relevant found").
3. **Hypotheses** — 2 or more, each with evidence for/against.
4. **Recommended next steps** — ordered, including a verification step.
5. **Confidence level** — with reasoning.

## 8. Acceptance Criteria

Each maps to the functional requirement in parentheses.

- **AC-1** (FR-1): Given an incident description with all 5 fields filled
  in, the app runs without asking for more information.
- **AC-2** (FR-2): The output references content from at least 3 of the 4
  source types in Section 6, when relevant content exists in each.
- **AC-3** (FR-3): Every hypothesis in the output names at least one
  specific source document it's based on.
- **AC-4** (FR-4): If a source file is empty or irrelevant to the incident,
  the output contains an explicit sentence saying so — not silence.
- **AC-5** (FR-5): The output contains 2 or more distinct hypotheses, each
  with both a "for" and an "against" (or "none found") statement.
- **AC-6** (FR-6): The "recommended next steps" section's first item is a
  verification/confirmation step, not a direct fix action.
- **AC-7** (FR-7): The output ends with one of High/Medium/Low plus at
  least one sentence of reasoning.
- **AC-8** (FR-8): No sentence in the output claims a real-world action was
  already performed by the app itself.
- **AC-9** (FR-9): Given a folder path as a command-line argument, the app
  reads the 3 source files from that path instead of `./data/`, using
  ordinary file I/O only — no network call is made to authenticate or
  retrieve anything.

## 9. Out of Scope (for this version)

- **Live API integration** with JIRA, SharePoint, or FreshService — meaning
  the app itself performing OAuth/authentication and calling Microsoft
  Graph, the JIRA REST API, or the FreshService API over the network.
  (Reading a local, possibly OneDrive-synced, folder per FR-9 is in scope
  and is NOT the same thing — no auth, no network call, just file I/O.)
- A GUI or web interface.
- Multi-incident batch processing.
- Persisting past RCA outputs for future reference.