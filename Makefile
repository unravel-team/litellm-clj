.PHONY: help repl nrepl test clean build install

help:
	@echo "Available targets:"
	@echo "  repl    - Start a Clojure REPL"
	@echo "  nrepl   - Start an nREPL server on port 7888"
	@echo "  test    - Run tests"
	@echo "  clean   - Remove target directory"
	@echo "  build   - Build the project"
	@echo "  install - Install to local Maven repository"

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

e2e:
	@echo "Running E2E provider tests..."
	@echo "Testing real API calls for each provider (configure API keys as needed)"
	@echo ""
	clojure -M:test -m kaocha.runner :e2e --reporter kaocha.report/documentation

clean:
	rm -rf target .cpcache

build:
	clojure -T:build jar

install:
	clojure -T:build install

# Streaming-specific targets
test-streaming:
	clojure -M test_streaming_manual.clj

compile:
	@echo "Compiling and checking syntax..."
	clojure -M -e "(require 'litellm.core) (require 'litellm.streaming) (println \"✓ Code compiles successfully\")"
