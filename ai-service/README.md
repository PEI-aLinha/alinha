# AI Service (Python + FastAPI, separate process)

RAG over the legislation (read-only knowledge base), redaction of personal data and provenance
(model, prompt version, sources, date — RNF14). Only called by the core's AI Gateway (contract in `contracts/ai/`).
No write access to platform data (RNF11). The LLM provider is abstracted here and processes data in the EU (RNF13).
