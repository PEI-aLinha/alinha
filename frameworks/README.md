# Framework plugins (catálogos versionados)

Dados, não código: cada framework é uma pasta com catálogos YAML versionados,
carregados pelo Compliance Engine e anunciados com `CatalogueVersionPublished`.

```
frameworks/
├── nis2-qnrcs/  v1/catalogue.yaml  v1/applicability.yaml
├── gdpr/
├── ai-act/
└── soc-cmm/
```

Uma nova versão de um regulamento = nova pasta `vN/`, nunca editar uma versão publicada
(os assessments antigos continuam a apontar para a versão com que foram feitos).

Ficam no repo principal (e não em submodules) porque não têm frontend nem backend próprios.
