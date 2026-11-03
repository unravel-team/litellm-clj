# Post-remediation security verification

## Source and result

The final offline run completed at **2026-11-03 20:46:58 UTC** with exit status **0**.
It tested committed source `b5e35d35fa3b603b72f4fc12fcb8d98e44deb249`.
The later evidence and ledger commits do not change application code.
The host was macOS 27.2, Clojure CLI 1.12.6.1673, and Temurin JDK 25.0.5.
The GitHub JDK 11/17/21 matrix was not run locally.

Evidence:

- [Structured result and artifact hashes](verification/result.json)
- [Verification output](verification/run.txt), with workspace paths normalized and terminal colors removed
- [Before/after file changes](verification/file-changes.json)
- [Seven checksummed Maven inventories](verification/inventory/)
- [Fresh Maven advisory report](verification/maven-audit.json)
- [Assessed action graph and bundle hashes](action-audit/assessed.json)

The original logs, full file hash manifests, source copy, and built JAR remain locally in:

```text
.verification/run-20261104-18093-135mzmf/
```

This directory is ignored by version control. Its raw log and manifest hashes are in the structured result.
The JAR was a local verification artifact, not a signed or published release.

## Commands and isolation

The main command was:

```bash
make offline-verification
```

The runner exported committed source through read-only `git archive` because jj has no archive command.
It copied local Git history for source/version metadata, but no hooks or original Git settings.
It used a new HOME and temporary directory, cleared inherited variables, and disabled AWS instance metadata.
It copied no personal Maven cache, Maven settings, Clojure settings, or provider credentials.

Preparation had network access to resolve the declared public Maven dependencies with `-P`.
It did not run their application entry points. Each JAR had to match the host inventory's SHA-256 digest.
This phase is separate from the network-denied test/build phase.

The checked-in macOS profile then denied network operations, reads under `/Users` outside the workspace, and outside writes except `/dev/null`.
The probe checked that credential variables were absent.
The OS denied a loopback socket connection, an outside sentinel read, and an outside sentinel write.
The outside sentinel remained unchanged.
Every child in the verification phase inherited that sandbox and the cleared environment.

The verification phase ran:

```bash
clojure -Srepro -M scripts/sandbox_probe.clj "$SENTINEL"
make lint
make test
make test-release
make coverage
clojure -Srepro -M:test:bedrock scripts/verify_bedrock.clj
make dependency-inventory
ruby test/security/workflows_test.rb
```

Both advisory audits also ran in the disposable source directory with a cleared environment after the offline phase.
These public read-only audits require network access. They are not part of the network-denied phase.
Set `WORK` to the printed disposable workspace path to repeat them:

```bash
cd "$WORK/source"
env -i HOME="$WORK/home" PATH=/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin \
  AWS_EC2_METADATA_DISABLED=true ruby scripts/audit_dependencies.rb target/security/*.tsv
env -i HOME="$WORK/home" PATH=/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin \
  AWS_EC2_METADATA_DISABLED=true ruby scripts/audit_actions.rb
```

No live provider, Bedrock client, GitHub Action, or Codecov uploader was executed.
Only public artifact/source reads and OSV queries used the network.
No push, remote repository update, publication, signing, tag creation, deployment, or credential change occurred.

## Gate results

| Gate | Result |
| --- | --- |
| Lint | 0 errors, 0 warnings |
| Unit suite | 267 tests, 1289 assertions, 0 failures |
| Coverage suite | Same 267 tests and 1289 assertions, 0 failures |
| Coverage output | 67.83% lines, 55.78% forms |
| Local JAR provenance | 1 test, 13 assertions, 0 failures/errors |
| Optional Bedrock transformations | 23 tests, 109 assertions, 0 failures/errors |
| Workflow policy | 4 tests, 111 assertions, 0 failures/errors |
| Maven inventories | All seven byte-identical to host inventories |
| Maven OSV audit | 190 package versions, 0 advisory-matched versions |
| Actions OSV audit | 217 runtime npm package versions, 23 advisory-matched versions (nonzero exit) |
| `make format`, `make check` | Targets absent, not counted as passing or failing code gates |

There is no enforced coverage percentage threshold. The report does not claim 75% coverage.
The action audit is **not clean**. [The security assessment](../security.md#workflow-dependencies) explains its remaining matches and the decision to keep the Codecov v4 token boundary.
The fresh action report has the same canonical content as the committed assessed report, excluding its query timestamp.
Its raw and canonical hashes are recorded in the result.
Maven and action results are time-specific database checks, not proof that vulnerabilities are absent.

## Processes, files, and egress

Process names were sampled every 50 ms within the verifier's process group.
Only expected shell, make, Java, and Ruby names appeared, including transient parenthesized names.
No unexpected executable was observed. Sampling can miss short-lived processes or a changed process group.
This was not an exhaustive process or system-call trace.

The final command-phase file manifest contained 2243 files.
There were 127 new files: 45 clj-kondo cache files, 25 Clojure CLI cache files, 54 build/coverage/inventory outputs, and 3 verifier evidence files.
No original file was removed. The only changed existing file was the scratch Git index, which Git refreshed during status checks.
No unexpected generated path was found. Before/after hashes do not show files created and removed between snapshots.
The later public audits created their own reports after this command-phase snapshot.

The profile denied direct network operations throughout tests and build.
Hato's unit fixture also fails on transport attempts, including exceptions caught by application code.
No unexpected destination was identified in the reviewed code paths.
A socket-denial probe and the profile are the egress evidence. There was no packet capture or live-provider DNS/redirect test.

## Limits and findings

Container access was refused by tool policy. The accepted alternative was a disposable macOS workspace with OS sandbox rules, not a container.
Early stricter read profiles aborted before verification. The final profile blocks personal home reads and outside writes but allows other system reads.
It does not isolate every host file, service, or native tool. Do not use it to run unreviewed hostile code.

Verification found three additional gaps, repaired before the final run:

- Dependency advisory matches led to issue `.10` and dependency upgrades.
- Residual external exception text led to issue `.11` and fixed diagnostic messages.
- Action runtime advisory matches led to issue `.12`, four action upgrades, and an explicit remaining-risk assessment.

The first clean-environment run also exposed skipped environment-dependent router setup assertions.
Follow-up `4c8caf08` replaced those branches with fake environment maps and made unstubbed router environment reads fail.
The final clean-environment and host suites both ran 1289 assertions.
The retained Codecov v4 wrapper now pins CLI v11.3.2 and stops on integrity failure rather than continuing with a warning.

No credential-exfiltration path was identified in the reviewed library and checked-in workflows after repair.
This finding covers request data, trusted settings, and library diagnostics.
This finding assumes trusted endpoint settings, reviewed main-branch code, and the required owner controls below.
It is not an assurance about malicious configured providers, arbitrary application callbacks/loggers, compromised upstream services, or every bundled third-party path.
The optional AWS credential chain and real provider authentication remain untested.

## Owner actions still required

Before enabling credentialed E2E, create and protect the `provider-e2e` environment with main-only branches, independent approval, and no self-approval.
Move provider credentials out of repository/organization secret scopes available to PR jobs.
Rotate any credentials exposed to earlier PR runs, review provider usage/billing, and inspect retained logs/artifacts.
Local YAML cannot create or prove these controls. No live GitHub environment or secret settings were inspected or changed.

Keep the Codecov token scoped to its guarded main-push upload step.
Monitor the documented upstream action advisory matches and review proposed dependency updates.
The release owner must separately approve provenance, signing, and publication under [the release procedure](../../DEPLOYMENT.md).
