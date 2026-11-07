# AI Service (Python, processo separado)

RAG · redaction · provenance. Só é chamado pelo AI Gateway do Core (contrato em `contracts/ai/`).
Devolve rascunhos — nunca decisões; tudo o que chega ao cliente passa por validação humana.
