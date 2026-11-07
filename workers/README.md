# Worker runtime (Python, processo separado)

- `job_consumer/` — lê jobs do Redis Streams (`contracts/jobs/`) e despacha para o collector certo.
- `document_sandbox/` — análise de ficheiros enviados, sem rede, só tipos permitidos (RNF11).

Os **collectors** de cada provider NÃO vivem aqui: estão no repo do próprio provider
(`plugins/provider/<id>/worker/`) e são instalados no runtime como pacotes Python.
