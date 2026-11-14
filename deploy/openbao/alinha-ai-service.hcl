# The AI Service only reads its own secrets (see ai-service/README.md).
path "secret/data/alinha/ai-service" { capabilities = ["read"] }
