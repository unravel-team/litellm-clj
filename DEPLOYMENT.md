# Release procedure

The canonical source repository is https://github.com/unravel-team/litellm-clj.
The Maven coordinate is `tech.unravel/litellm-clj`.
The `VERSION` file contains `major.minor`. The build adds the Git commit count as the patch number.
The commit count alone is not a unique source identifier across branches.

No publication, tag creation, signing, or remote change is part of the security remediation.
The owner must approve those actions separately. This document describes the release maintainer's procedure.
Past unsigned commits and published artifacts were not rewritten or replaced.

## Prepare reviewed source

1. Start from the reviewed release change in a complete repository with its full history.
2. Run `jj status` and `jj diff`.
3. Make sure that no uncommitted source changes or unexpected files remain.
4. If a version bump is needed, use `make release-major` or `make release-minor` and review the resulting commit.
5. With the maintainer's signing configuration, sign the release commit through `jj sign`.
6. Record the full signed commit ID. Signing changes the commit ID, so do this before the release build.

Use a fresh empty jj change above that release commit for local checks.
The build reads Git HEAD in the colocated repository.
Make sure that Git HEAD names the reviewed signed release commit, not an unrelated checkout.
A shallow checkout changes the commit count and cannot reproduce the version.

## Build and inspect

Run the following local commands. They do not publish artifacts.
Dependency resolution and the audit require network access to public package repositories and OSV.
The unit tests use fakes and do not contact providers.

```sh
make lint
make test
make test-release
make coverage
make dependency-audit
make current-version
```

`make test-release` builds the JAR and tests its POM and embedded source metadata.
The build cleans `target/`, so generate dependency inventories after the JAR build.
The POM uses canonical HTTPS source URLs and the full source commit ID as its SCM tag.
`META-INF/litellm-clj/build-info.edn` records the repository, source revision, version, and dirty flag.
If the dirty flag is true, do not publish the JAR.

For each release, archive the JAR, POM, resolved inventories, audit report, exact CLI/JDK versions, and gate results.
Record the complete source revision beside these files.
The release maintainer must compare the inventories against independently resolved inventories from a clean machine.
Investigate changed dependency hashes before publication.

Generate checksums after the final build:

```sh
shasum -a 256 target/litellm-clj-*.jar \
  target/classes/META-INF/maven/tech.unravel/litellm-clj/pom.xml \
  target/security/*.tsv > target/SHA256SUMS
```

Sign `SHA256SUMS` with the maintainer's approved signing key.
A checksum without a trusted signature detects accidental changes but does not authenticate the publisher.

## Publish with owner approval

Create a signed annotated `v<version>` tag on the exact reviewed source commit.
Use the approved Git signing tooling for annotated tags. jj does not provide an equivalent signed annotated-tag command.
Make sure that both the commit signature and tag signature belong to an approved maintainer.
Do not move an existing release tag or silently replace a published artifact.

Use a scoped Clojars deploy token, not an account password.
Supply it only to the approved publishing process. Do not put it in source, shell history, or CI pull-request jobs.
`make deploy` rebuilds the JAR before upload. The checkout, toolchain, version, and inventories must remain unchanged.
Recompute checksums for the actual uploaded bytes, since rebuilding can change archive timestamps.
Publish the signed checksums and evidence beside the artifacts through the approved release process.

After publication, download the artifact from Clojars and compare its checksum with the signed release manifest.
Read its POM and embedded build information. Make sure that the recorded revision is the reviewed signed source commit.
Consumers can use that full revision even if they do not trust the version's commit count.
If a release is wrong, publish a corrected version with a new signed tag and an explanation.
Do not delete or rewrite release history as a substitute for a correction.

GitHub environment protections and provider-secret rotation remain separate owner actions documented in [security.md](doc/security.md).
