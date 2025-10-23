# Testing Streaming Implementation

This guide walks you through testing the streaming functionality with real LLM API calls.

## Prerequisites

1. **API Keys Required:**
   - OpenAI API Key (required for tests 1-3)
   - Anthropic API Key (optional for test 4)

2. **Get Your API Keys:**
   - OpenAI: https://platform.openai.com/api-keys
   - Anthropic: https://console.anthropic.com/settings/keys

## Step 1: Set Up Environment Variables

### On macOS/Linux:
```bash
# Set OpenAI API key (required)
export OPENAI_API_KEY="sk-your-openai-key-here"

# Set Anthropic API key (optional)
export ANTHROPIC_API_KEY="sk-ant-your-anthropic-key-here"
```

### On Windows (Command Prompt):
```cmd
set OPENAI_API_KEY=sk-your-openai-key-here
set ANTHROPIC_API_KEY=sk-ant-your-anthropic-key-here
```

### On Windows (PowerShell):
```powershell
$env:OPENAI_API_KEY="sk-your-openai-key-here"
$env:ANTHROPIC_API_KEY="sk-ant-your-anthropic-key-here"
```

## Step 2: Run the Test Script

### Option A: Using Clojure CLI
```bash
clojure -M test_streaming_manual.clj
```

### Option B: Start a REPL and Run Tests Individually
```bash
clojure -M:repl
```

Then in the REPL:
```clojure
(load-file "test_streaming_manual.clj")

;; Run individual tests
(test-openai-streaming)
(test-callback-streaming)
(test-blocking-collection)
(test-anthropic-streaming)  ; Only if you have ANTHROPIC_API_KEY set
```

## Step 3: What to Expect

### Test 1: Basic OpenAI Streaming
```
Test 1: OpenAI Streaming
----------------------------------------
Response: 1
2
3
4
5
✓ Stream completed. Received XX chunks
Test 1: ✓ PASSED
```

### Test 2: Callback-Based Streaming
```
Test 2: Callback-Based Streaming
----------------------------------------
Response: Hello World
✓ Callback completed. Total chunks: XX
Test 2: ✓ PASSED
```

### Test 3: Blocking Collection
```
Test 3: Blocking Collection
----------------------------------------
Collecting stream...
Content: Testing 123
Total chunks: XX
Test 3: ✓ PASSED
```

### Test 4: Anthropic Streaming (Optional)
```
Test 4: Anthropic Streaming (Optional)
----------------------------------------
Response: Anthropic works
Test 4: ✓ PASSED
```

Or if no Anthropic key:
```
Test 4: Anthropic Streaming (Optional)
----------------------------------------
ANTHROPIC_API_KEY not set - skipping
Test 4: ⊘ SKIPPED
```

## Troubleshooting

### Error: "OPENAI_API_KEY not set"
**Solution:** Make sure you've exported the environment variable in the same terminal session where you're running the test.

### Error: "Authentication failed" or "401"
**Solution:** 
- Verify your API key is correct
- Check if your API key has been activated
- Ensure you have credits/quota remaining

### Error: "Rate limit exceeded" or "429"
**Solution:**
- Wait a moment and try again
- You may have hit your API rate limit

### Tests hang or timeout
**Solution:**
- Check your internet connection
- API services might be slow - tests have 5-second timeouts
- Try running tests one at a time in a REPL

## Verifying Success

All tests should show:
- ✓ PASSED for tests 1-3 (if OpenAI key is set)
- ✓ PASSED or ⊘ SKIPPED for test 4 (depending on Anthropic key)

If any test shows ❌ FAILED, check:
1. Your API keys are valid
2. You have internet connectivity
3. The API services are operational
4. Check the error message for specific issues

## Quick Visual Test in REPL

For a quick manual verification, try this in a REPL:

```clojure
(require '[litellm.core :as llm])
(require '[litellm.streaming :as streaming])
(require '[clojure.core.async :refer [go-loop <!]])

;; Set your API key
(def api-key (System/getenv "OPENAI_API_KEY"))

;; Simple streaming test
(let [ch (llm/completion 
           :model "openai/gpt-4o-mini"
           :messages [{:role :user :content "Count to 3"}]
           :stream true
           :api-key api-key
           :max-tokens 30)]
  (go-loop []
    (when-let [chunk (<! ch)]
      (when-let [content (streaming/extract-content chunk)]
        (print content)
        (flush))
      (recur))))

;; You should see numbers appearing one by one
```

## Success Criteria

✅ **Streaming is working correctly if:**
1. You see text appearing incrementally (not all at once)
2. No error messages are displayed
3. All tests report ✓ PASSED
4. Stream completes successfully

## Next Steps

Once tests pass, the streaming implementation is verified and ready for:
- Integration into your application
- Production deployment
- Further customization based on your needs

## Need Help?

If tests are failing:
1. Check the error messages carefully
2. Verify API keys are correct and active
3. Ensure you have API quota/credits
4. Try a simple non-streaming request to verify API connectivity
