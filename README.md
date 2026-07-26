# The sidecar — a mock orchestrator on your laptop

Your module only ever hears from one caller: the orchestrator. It is not running on your
laptop, and waiting for it is not a development loop. So this box plays it.

It does two things and deliberately nothing else:

1. **Sends applications to your module** — `POST http://your-module/api/v1/applications`, using
   the same envelope the real orchestrator sends.
2. **Receives your module's status update** — it serves `PUT /api/v1/applications/{applicationId}`, the endpoint your
   module reports its decision to.

Point 2 is the reason this exists. Without something listening on `ORCHESTRATOR_URL`, your
callback fails with a logged warning and **you can only ever see half your own contract**. Here
both halves land in one row of one table and appear on one page.

```
sidecar  ──POST /api/v1/applications──▶  your module
         ◀─────── 202 in-progress ──────      │  decides off-thread
         ◀──PUT /api/v1/applications/{id}─────┘
         ─────── 200 received ──────────▶
```

---

## Start here

The sidecar is a service in your module repo's `docker-compose.yml`, and there is **no copy of
it in your repo** — compose builds it straight from this repository:

```yaml
  sidecar:
    build: https://github.com/gjavolce/neobank-sidecar.git#v1
```

So you never clone this, never copy it, and never edit it. You just run your stack:

```bash
docker compose up --build          # your backend, your UI, MySQL, and the sidecar
open http://localhost:9000         # the sidecar
```

**The first build takes about six minutes** — it compiles this project on your machine. After
that it is cached and costs ~4 seconds, until the sidecar itself changes. To pick up a new
version, `docker compose up --build sidecar`.

**It is dark with hot orange chrome, and that is deliberate.** Your module's UI is light, glass
and mint. This one sits open beside it all day, and two windows that look alike are two windows
you mix up — which here means reading the orchestrator's log and thinking it is your module's.
**Orange is the orchestrator side; glass is the module side.**

Then: pick **SIM-01**, press **Send**, and watch the row. You should see `202` in the *Ack*
column immediately and a decision in the *Callback* column a couple of seconds later. That gap
is the async contract — it is why your module answers `202` before it knows anything.

Prefer curl? The whole box is three endpoints:

```bash
curl -s localhost:9000/api/v1/scenarios | jq '.scenarios[].id'          # what is in the library
curl -sX POST localhost:9000/api/v1/dispatch \
     -H 'Content-Type: application/json' -d '{"scenarioId":"SIM-01"}'   # send one
curl -s localhost:9000/api/v1/dispatches | jq '.[0]'                    # what came back
```

**Port 9000 is not arbitrary.** Your module's `ORCHESTRATOR_URL` already defaults to
`http://localhost:9000`, so a module you run from your IDE finds the sidecar with no
configuration at all.

### If the sidecar will not start

Almost always one thing: MySQL creates `sidecar_db` from `db/init/*.sql`, and it runs those
**only on a fresh data directory**. If you used the stack before the sidecar existed, your
volume already exists and the schema was never created. The sidecar says so in its own log. Fix:

```bash
docker compose down -v && docker compose up --build
```

Safe — the volume holds nothing but a development log.

---

## The API

Your module calls exactly one of these. The rest are for you and the UI.

| | Endpoint | What it is |
|---|---|---|
| **contract** | `PUT /api/v1/applications/{applicationId}` | **What your module calls.** Three fields — `serviceId`, `status`, `comment` — with the id in the URL. Answers `200 {"received":true,…}`. |
| tool | `GET /api/v1/scenarios` | The library, with every envelope attached. |
| tool | `POST /api/v1/dispatch` | Send one: `{scenarioId}` or `{envelope}`, plus optional `moduleUrl` and `freshId`. |
| tool | `GET /api/v1/dispatches` | The exchange log, newest first. |
| tool | `DELETE /api/v1/dispatches` | Clear the log. |
| ops | `GET /health` · `GET /info` | Up, and where it is pointing. |

That `PUT` is a **copy of the real orchestrator's endpoint** and has to stay exact — the path, the
three fields, and the always-`200`. If it were more forgiving than the orchestrator, a
broken module could look finished here and fail in the system stack. So: a late, duplicate or
misdirected callback is accepted and recorded as an `unsolicited` row rather than refused.

### Where your module is

`MODULE_URL` (default `http://backend:8080`) says where dispatches go. The UI has a **module
base URL** field that overrides it per send — no restart. The one you will actually need:

| Your module is running… | Use |
|---|---|
| in compose, alongside the sidecar | `http://backend:8080` (the default) |
| in IntelliJ, on your host | `http://host.docker.internal:8080` |

---

## The scenario library

**26 applications. Every one is SIM-01 with a single thing changed**, so a surprise is always
traceable to one field. Money is whole GBP; the product catalogue, supported/excluded tax
residencies and reason codes are `api-contract.md` §3 and §4.

| Id | What it is | Modules it is aimed at | Reason codes it should provoke | HTTP |
|---|---|---|---|---|
| SIM-01 | Happy path — rewards card | all | `VER_ALL_CHECKS_PASSED` · `POL_ALL_CHECKS_PASSED` · `KYC_VERIFIED` · `SCR_NO_MATCH` · `CRE_APPROVED` · `AGR_SIGNED` · `ACC_OPENED` · `CRD_ISSUED` | 202 |
| SIM-02 | Happy path — standard card, second applicant | all | the same eight | 202 |
| SIM-03 | Age boundary — exactly 18 today | verification, credit | `VER_ALL_CHECKS_PASSED` · `CRE_APPROVED` | 202 |
| SIM-04 | Age boundary — one day short of 18 | verification | `VER_AGE_BELOW_MINIMUM` | 202 |
| SIM-05 | Limit boundary — exactly the product maximum | verification, credit | `VER_ALL_CHECKS_PASSED` · `CRE_APPROVED` · `CRE_LIMIT_CAPPED_TO_REQUEST` | 202 |
| SIM-06 | Limit boundary — 500 over the product maximum | verification, credit | `VER_LIMIT_OUTSIDE_PRODUCT_RANGE` · `CRE_LIMIT_CAPPED_TO_BAND_MAX` | 202 |
| SIM-07 | Terms and conditions not accepted | verification, agreement | `VER_TERMS_NOT_ACCEPTED` · `AGR_PENDING_SIGNATURE` | 202 |
| SIM-08 | Required fields missing | verification, kyc, card | `VER_MISSING_FIELD` | 202 |
| SIM-09 | Field formats invalid | verification | `VER_INVALID_FIELD` | 202 |
| SIM-10 | Tax residency on the excluded list (US) | policy | `POL_TAX_RESIDENCY_EXCLUDED` | 202 |
| SIM-11 | Tax residency not on the supported list (BR) | policy | `POL_TAX_RESIDENCY_UNSUPPORTED` | 202 |
| SIM-12 | Existing customer applying for a second card | policy, account | `POL_EXISTING_PRODUCT_HELD` · `ACC_DUPLICATE_PREVENTED` | 202 |
| SIM-13 | Identity document expired | kyc | `KYC_DOCUMENT_EXPIRED` | 202 |
| SIM-14 | ID provider unavailable | kyc | `KYC_PROVIDER_UNAVAILABLE` · `KYC_FAILED_OVER_TO_SECONDARY` | 202 |
| SIM-15 | Sanctions list — exact name match | screening | `SCR_EXACT_MATCH` | 202 |
| SIM-16 | Sanctions list — partial name match | screening | `SCR_PARTIAL_MATCH` · `SCR_CLEARED_BY_ANALYST` | 202 |
| SIM-17 | High-risk country | screening | `SCR_HIGH_RISK_COUNTRY` | 202 |
| SIM-18 | Income below the product minimum | credit | `CRE_INCOME_BELOW_MINIMUM` | 202 |
| SIM-19 | Affordability boundary — DTI 0.44 | credit | `CRE_APPROVED` | 202 |
| SIM-20 | Affordability boundary — DTI 0.46 | credit | `CRE_AFFORDABILITY_EXCEEDED` | 202 |
| SIM-21 | Card delivered to a different address | card | `CRD_ISSUED` | 202 |
| SIM-22 | Alternate delivery requested, no address given | card | `CRD_DELIVERY_ADDRESS_INVALID` | 202 |
| SIM-23 | Unknown fields must be ignored | all | `VER_ALL_CHECKS_PASSED` | 202 |
| SIM-24 | Unknown product code (the b00 demo generator's) | all but policy and screening | `VER_INVALID_FIELD` | 202 |
| SIM-25 | The same application id, sent twice | all | — | 202 |
| SIM-26 | Invalid envelope — no application id | all | — | **400** |

`index.json` carries the same table **plus the arithmetic** — the exact DTI sum, why a limit is
inside or outside a band — so you can check a rule against a number instead of squinting at it.

### Five that repay reading before you write a rule

- **SIM-06** is rejected by verification *and* capped by credit, and both are right. Steps are
  independent and never read each other's result (`api-contract.md` §1, rule 3).
- **SIM-12** is the same human as SIM-01 under a new application id. Send SIM-01 first. It is the
  only scenario that tells you whether module 2's one-card-per-customer rule keys on the person
  rather than on `applicationId`.
- **SIM-19 / SIM-20** differ by £50 a month. `>` versus `>=`, or rounding, shows up here and
  nowhere else.
- **SIM-22** asks for delivery to an address that is not there. A module that reads
  `delivery.address` without a null check throws — and **a module that throws never calls back**,
  so the journey sits until it times out. That failure mode is worth seeing once.
- **SIM-26** must be a `400`. If it returns `202`, your validation is gone.

### Two conventions the corpus plants

Integration modules need a way to make their mock misbehave on demand, so the corpus fixes the
trigger rather than letting ten teams each invent one:

- `identityDocument.documentId` = **`ZZ0000000`** → your mock ID provider should fail (SIM-14).
- **Viktor Petrov, born 1975-05-14** → the planted exact sanctions hit; put him on your mock
  watchlist (SIM-15). **Viktoria Petrova** is the near miss (SIM-16).

### Dates do not rot

Anything that must stay relative to today is a token — `{{today}}`, `{{today-18y}}`,
`{{today-18y+1d}}` — resolved when the library loads. The age-boundary pair (SIM-03/SIM-04) used
to be fixed dates, and the day after the corpus was written "one day short of 18" turned 18 and
silently started testing the passing side. `ScenarioLibraryTest` now asserts one is exactly 18
and the other is 17, whatever day it runs.

A date that is *not* relative stays literal: SIM-13's document expired in 2025 and stays expired.

### Sending the same application twice

Leave **unique id per send** off and a re-send of SIM-01 is a *retry* — your module should reuse
the row it already has, because that is what the orchestrator retrying looks like. SIM-25 exists
to make you prove it. Turn the checkbox on and each send gets a new id, rewriting **both** copies
(the envelope's and the nested `application.applicationId`) — they must move together, or your
module logs one id and stores the other.

### Adding your own applications

The corpus is baked into the image compose builds for you, so there are no files of it on your
disk to edit. Mount your own instead:

```yaml
  sidecar:
    environment:
      SCENARIOS_DIR: /scenarios
    volumes:
      - ./my-scenarios:/scenarios:ro
```

Any `*.json` there is loaded on top of the shipped set; a file with the same name as a shipped one
replaces it. Copy the nearest existing scenario and change **one** thing — that is the pattern the
whole corpus follows and it is what makes a failure diagnosable.

For a one-off you do not need any of that: **edit the envelope in the textarea and send it.**
*Reset to file* undoes it.

### Using them in your own tests

A rule test that reads the same application the demo sends is worth two that invent their own.
Copy the scenario you care about into your module's `src/test/resources/` and load it:

```java
var envelope = new ObjectMapper().readValue(
        new ClassPathResource("scenarios/20-affordability-just-over.json").getInputStream(),
        Map.class);
```

---

## Two known drifts in the corpus

- **`command` is `process-application` everywhere.** That is what the running orchestrator sends.
  `api-contract.md` §2.3 specifies a per-domain command (`verify-application`,
  `screen-application`, …) and the orchestrator does not do that yet. Read the command; do not
  switch on it.
- **SIM-24 carries `CREDIT_CARD_PREMIUM`**, which is not in the locked catalogue — the b00 demo
  generator emits it.

Both are tracked drift between `api-contract.md` and the code, and both are why **your module
must not crash on input it did not expect.**

---

## For the instructor — releasing a change

This repository is the only copy. **No module repo contains sidecar source**, so there is
nothing to drift and no ten-way sync.

```bash
./mvnw -B test                      # 26 unit + slice tests, no Docker
./mvnw -B verify -DskipITs=false    # + 4 integration tests on real MySQL 8.4
```

**There is no registry and no image to publish.** Teams reference this repo as a Docker build
context, so `git push` is the whole distribution mechanism — and `v1` is the gate between your
main and their machines:

```bash
git push                                  # main can be work in progress; nobody is on it
git tag -f v1 && git push -f --tags       # NOW every team gets it on `up --build`
```

Two reasons it is a tag and not `#main`: a broken commit on main would otherwise be a broken
`docker compose up` for ten teams simultaneously, and moving a tag deliberately is the only
"release" step there is. Teams that want to follow main can change their own compose line.

**This must stay a repository with no submodules.** A git build context clones submodules
recursively, and BuildKit has no SSH credentials — an SSH-URL submodule anywhere in here makes
every team's build fail with a confusing `Please make sure you have the correct access rights`.
(That is exactly why the sidecar does not live inside `attempt-b00`, which has one.)

**Keep this repository public.** Not a settings toggle to remember — it is public from creation
and BuildKit clones it anonymously. If it were private, ten builds would fail at once with a
git auth error and no hint.

### What it deliberately is not

It has **no state machine, no step ordering, no timeout sweeper and no retry.** The real
orchestrator (`attempt-b00`) has all four. Every one of them added here would be a second
implementation of something that already exists, and a mock that drifts from the thing it mocks is
worse than no mock at all. The value is narrow and real: the contract is two-way, and until
something answers on `/api/v1/applications/{applicationId}` a team can only see half of it.

It also serves **modules 1–8** only. Modules 9 and 10 consume the journey rather than serve a step,
so there is nothing here to send them; `GET /api/v1/dispatches` is the miniature of the journey
read API those two teams need, and the natural place to grow it.
