I suggest we tackle these in three small, focused batches:

Batch 1: The "Last Mile" of Configuration & Docs

- Finalize CpElasticsearchProperties validation.
- Add the Troubleshooting/Runbook section to the integration guide.
- Verify production defaults for logRequestPayloads.

Batch 2: Observable Stability

- Implement cache hit ratio metrics.
- Audit ExensioClient and ElasticsearchLogService for any potential resource leaks in catch blocks.

Batch 3: Testing & Verification (The "Confidence" Phase)

- Create a suite of unit tests for the CircuitBreaker and Retry logic.
- Create integration tests for the ExensioLoadMonitor (mocking API timeouts and 500s to trigger DLQ/Retry).
