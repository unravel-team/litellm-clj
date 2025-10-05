# Deployment Instructions for v0.1.0

## Current Status ✅

The following tasks have been completed:

1. ✅ **Build Configuration** - Created `build.clj` with tools.build setup
2. ✅ **Documentation** - Updated README with comprehensive examples and Clojars installation
3. ✅ **Release Notes** - Created CHANGELOG.md and RELEASE_NOTES.md
4. ✅ **JAR Build** - Successfully built `target/litellm-clj-0.1.0.jar`
5. ✅ **POM Generation** - Generated POM file with proper metadata
6. ✅ **Git Commit** - Committed all release files
7. ✅ **Git Tag** - Created v0.1.0 tag locally

## Remaining Tasks 🔄

### 1. Set up Clojars Credentials

Before deploying, you need to configure your Clojars authentication. Choose one method:

#### Option A: Environment Variables (Recommended)
```bash
export CLOJARS_USERNAME=your-username
export CLOJARS_PASSWORD=your-deploy-token
```

**Note:** Use a deploy token, not your password. Get one from: https://clojars.org/tokens

#### Option B: Maven Settings File
Create or edit `~/.m2/settings.xml`:
```xml
<settings>
  <servers>
    <server>
      <id>clojars</id>
      <username>your-username</username>
      <password>your-deploy-token</password>
    </server>
  </servers>
</settings>
```

### 2. Deploy to Clojars

Once credentials are set, run:
```bash
clojure -T:build deploy
```

This will upload:
- `tech.unravel/litellm-clj-0.1.0.jar`
- `tech.unravel/litellm-clj-0.1.0.pom`

### 3. Sync Git Repository

There's a conflict with the remote repository. To resolve:

```bash
# Pull the remote changes
git pull origin main --no-rebase

# If there are conflicts, resolve them manually
# The main conflict is in README.md where remote has old installation instructions

# Then push your changes
git push origin main

# Push the tag
git push origin v0.1.0
```

### 4. Create GitHub Release

After pushing the tag, create a GitHub release:

```bash
gh release create v0.1.0 \
  --title "Release v0.1.0" \
  --notes-file RELEASE_NOTES.md \
  target/litellm-clj-0.1.0.jar
```

Or manually at: https://github.com/unravel-team/clj-litellm/releases/new

## Verification Steps

After deployment:

1. **Verify Clojars Publication:**
   - Visit: https://clojars.org/tech.unravel/litellm-clj
   - Confirm version 0.1.0 is listed

2. **Test Installation:**
   ```bash
   # Create a test project
   mkdir test-litellm
   cd test-litellm
   
   # Create deps.edn
   echo '{:deps {tech.unravel/litellm-clj {:mvn/version "0.1.0"}}}' > deps.edn
   
   # Start REPL and test
   clojure -M -e "(require '[litellm.core :as litellm]) (println \"Success!\")"
   ```

3. **Verify GitHub Release:**
   - Check: https://github.com/unravel-team/clj-litellm/releases
   - Confirm v0.1.0 tag and release are visible

## Rollback Plan

If issues arise:

1. **Delete Clojars Release:**
   - Contact Clojars support to remove the version
   - Or deploy a new patch version with fixes

2. **Delete Git Tag:**
   ```bash
   git tag -d v0.1.0
   git push origin :refs/tags/v0.1.0
   ```

## Post-Release Tasks

After successful deployment:

1. Update project version to 0.2.0-SNAPSHOT in `build.clj`
2. Announce release on relevant channels
3. Monitor for issues and user feedback
4. Update documentation based on feedback

## Release Artifacts

The following files have been created:

- `build.clj` - Build and deployment configuration
- `CHANGELOG.md` - Version history
- `RELEASE_NOTES.md` - Detailed release information
- `target/litellm-clj-0.1.0.jar` - Compiled JAR
- `target/classes/META-INF/maven/tech.unravel/litellm-clj/pom.xml` - Maven POM

## Support

If you encounter issues:

1. Check Clojars deploy tokens are valid
2. Verify network connectivity to Clojars
3. Review build logs in `/tmp/clojure-*.edn`
4. Consult: https://github.com/clojure/tools.build
