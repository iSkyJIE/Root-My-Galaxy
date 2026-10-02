# Translations

The app's strings live in [`app/src/main/res/values/strings.xml`](../app/src/main/res/values/strings.xml),
and translations come from Crowdin. The project is **Root-My-Galaxy-Next**, source language `en`,
mapped by [`crowdin.yml`](../crowdin.yml).

The eleven languages the app already carries — German, French, Japanese, Korean, Portuguese
(Brazil), Russian, Turkish, Uzbek, Vietnamese and both Chinese scripts — are the target languages
of that project. Their folders were translated by hand before Crowdin was here, and are seeded into
it the first time the workflow below runs with **seed translations** ticked.

## What is still needed

**The project does not exist in Crowdin yet.** Creating it is the one thing that cannot be done
from here: this account holds an open-source licence - unlimited projects, strings and members on
the free plan - but Crowdin grants that licence **per project**, and its API answers
`403 Request an open source license to create another open source project` until the new one has
been granted its own. It is applied for on the website and read by a person, so it takes as long as
it takes:

- form: <https://crowdin.com/product/for-open-source>
- what it wants: an OSI-approved licence (this repository is Apache-2.0), public sources, no
  commercial product, the project lead, and a project that has been going for at least three months

Two things have to be in place before the workflow below does anything:

| Where | What | Notes |
| --- | --- | --- |
| *Settings → Secrets and variables → Actions → **Variables*** | `CROWDIN_PROJECT_ID` | The number in the project's URL. Every job skips itself until this is set, so the workflow can sit in the tree doing nothing. |
| *Settings → Secrets and variables → Actions → **Secrets*** | `CROWDIN_PERSONAL_TOKEN` | A Crowdin personal access token with *Source files & strings* and *Translations* at Read and Write. Already set. |

## What runs when

| When | What |
| --- | --- |
| A push to `main` that changes `values/strings.xml` | `upload sources` — Crowdin learns what there is to translate |
| Nightly at 04:23, or *Run workflow* | `download translations` into a pull request on the `l10n` branch |
| *Run workflow* with **seed translations** ticked | uploads the repository's own files as translations, for a language Crowdin does not have yet |
| That pull request | merged automatically, squashed, if it only touches `values-*/strings.xml` and every file in it holds at least one string |

So the loop is: change a string → push → Crowdin has it → a translator writes it → the next nightly
opens a pull request → it merges itself. The two things that remain a person's are the push and the
translating.

## Adding a language

Three edits, and they have to happen in this order:

1. **Create the folder** — `app/src/main/res/values-<code>/strings.xml`. The check that guards the
   translation pull requests refuses any file whose folder does not exist, which is the point: a
   language should appear in the app because someone decided it should, never because an export
   brought it along.
2. **Add the language to the Crowdin project** and put it in `languages_mapping` in
   [`crowdin.yml`](../crowdin.yml) — Crowdin's language id against the code the folder is named for.
3. **Seed it** — run the workflow by hand with *seed translations* ticked, so the folder's existing
   translations become that language's in Crowdin rather than starting from nothing.

Adding a language costs hosted words on the free plan — the source words multiplied by the number
of target languages — so a language nobody is translating costs as much as one they are. The
open-source licence removes the charge; it does not remove the reason to keep the list to languages
with translators behind them.
