.PHONY: help repl nrepl test test-ci coverage lint clean build install deploy e2e test-streaming compile check-reflection check-clean-worktree .release-commit release-major release-minor current-version

# The library version is maj.min.x: maj.min lives in the VERSION file,
# x is the git commit count at build time (see build.clj).
VERSION_FILE := VERSION

help:
	@echo "Available targets:"
	@echo "  repl    - Start a Clojure REPL"
	@echo "  nrepl   - Start an nREPL server on port 7888"
	@echo "  test    - Run tests"
	@echo "  clean   - Remove target directory"
	@echo "  build   - Build the project"
	@echo "  install - Install to local Maven repository"
	@echo "  deploy  - Deploy to Clojars"
	@echo "  release-major - Bump major version and commit the bump"
	@echo "  release-minor - Bump minor version and commit the bump"
	@echo "  current-version - Print current library version"
	@echo "  check-reflection - Fail on reflection warnings in src"

repl:
	clojure -M:repl

nrepl:
	@echo "Starting nREPL server on port 7888..."
	clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.1.0"} cider/cider-nrepl {:mvn/version "0.45.0"}}}' \
		-M -m nrepl.cmdline --middleware '["cider.nrepl/cider-middleware"]' --port 7888

test:
	clojure -M:test -m kaocha.runner

test-ci:
	clojure -M:test -m kaocha.runner --reporter kaocha.report/documentation

coverage:
	clojure -M:test:coverage

lint:
	clojure -M:kondo --lint src test

# Reflection the JVM tolerates breaks consumers compiling to GraalVM
# native images, so warnings fail the build. Loads every src namespace.
check-reflection:
	@warnings=$$(clojure -M:dev -e "(set! *warn-on-reflection* true) (require '[clojure.tools.namespace.find :as find]) (doseq [n (find/find-namespaces-in-dir (java.io.File. \"src\"))] (require n))" 2>&1 >/dev/null | grep "Reflection warning" || true); \
	if [ -n "$$warnings" ]; then echo "$$warnings"; exit 1; fi
	@echo "No reflection warnings"

e2e:
	@echo "Running E2E provider tests..."
	@echo "Testing real API calls for each provider (configure API keys as needed)"
	@echo ""
	clojure -M:test -m kaocha.runner :e2e --reporter kaocha.report/documentation

clean:
	rm -rf target .cpcache

check-clean-worktree:
	@if [ -d .jj ]; then \
		if [ -n "$$(jj diff --summary)" ]; then \
			echo "Error: working copy is not clean. Commit or abandon changes first."; \
			exit 1; \
		fi; \
	else \
		git diff --quiet && git diff --cached --quiet || { \
			echo "Error: working tree is not clean. Commit or stash changes first."; \
			exit 1; }; \
	fi

.release-commit:
	@if [ -d .jj ]; then \
		jj commit $(VERSION_FILE) -m "chore(release): bump version to $$(cat $(VERSION_FILE))"; \
	else \
		git add $(VERSION_FILE) && \
		git commit -m "chore(release): bump version to $$(cat $(VERSION_FILE))"; \
	fi
	@echo "Released version: $$(cat $(VERSION_FILE)).$$(git rev-list --count HEAD 2>/dev/null || echo '?')"

release-major: check-clean-worktree
	@maj=$$(cut -d. -f1 $(VERSION_FILE)); \
	echo "$$((maj+1)).0" > $(VERSION_FILE)
	@$(MAKE) .release-commit

release-minor: check-clean-worktree
	@maj=$$(cut -d. -f1 $(VERSION_FILE)); min=$$(cut -d. -f2 $(VERSION_FILE)); \
	echo "$$maj.$$((min+1))" > $(VERSION_FILE)
	@$(MAKE) .release-commit

current-version:
	@echo "$$(cat $(VERSION_FILE)).$$(git rev-list --count HEAD 2>/dev/null || echo '?')"

build:
	clojure -T:build jar

install:
	clojure -T:build install

deploy:
	clojure -T:build deploy

# Streaming-specific targets
test-streaming:
	clojure -M test_streaming_manual.clj

compile:
	@echo "Compiling and checking syntax..."
	clojure -M -e "(require 'litellm.core) (require 'litellm.streaming) (println \"✓ Code compiles successfully\")"
