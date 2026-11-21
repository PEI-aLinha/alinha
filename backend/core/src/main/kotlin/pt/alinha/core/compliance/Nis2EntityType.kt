package pt.alinha.core.compliance

import pt.alinha.core.compliance.Nis2Annex.I
import pt.alinha.core.compliance.Nis2Annex.II

/** Annexes of the Regime Jurídico da Cibersegurança (Decree-Law 125/2025). */
enum class Nis2Annex {
    /** Sectors of high criticality ("Setores de importância crítica"). */
    I,

    /** Other critical sectors ("Outros setores críticos"). */
    II,
}

/**
 * Entity types listed in annexes I and II of the Regime Jurídico da Cibersegurança (Decree-Law 125/2025).
 * [reference] points to the annex row (sector number and subsector letter); [label] is the plain-language
 * name shown to the user, who picks the types that describe the organization.
 * [naceDivision] is set only where the annex itself names a NACE Rev. 2 division.
 */
enum class Nis2EntityType(
    val annex: Nis2Annex,
    val reference: String,
    val label: String,
    val naceDivision: String? = null,
) {
    // Annex I, 1. Energy
    ELECTRICITY_SUPPLIER(I, "Anexo I, 1.a)", "Comercialização de eletricidade"),
    ELECTRICITY_DISTRIBUTION_OPERATOR(I, "Anexo I, 1.a)", "Operador da rede de distribuição de eletricidade"),
    ELECTRICITY_TRANSMISSION_OPERATOR(I, "Anexo I, 1.a)", "Operador da rede de transporte de eletricidade"),
    ELECTRICITY_PRODUCER(I, "Anexo I, 1.a)", "Produção de eletricidade"),
    ELECTRICITY_MARKET_OPERATOR(I, "Anexo I, 1.a)", "Operador nomeado do mercado da eletricidade"),
    ELECTRICITY_MARKET_PARTICIPANT(
        I, "Anexo I, 1.a)",
        "Agregação, resposta da procura ou armazenamento de energia no mercado da eletricidade",
    ),
    CHARGING_POINT_OPERATOR(I, "Anexo I, 1.a)", "Operador de pontos de carregamento de veículos elétricos"),
    DISTRICT_HEATING_COOLING(I, "Anexo I, 1.b)", "Aquecimento ou arrefecimento urbano"),
    OIL_PIPELINE_OPERATOR(I, "Anexo I, 1.c)", "Operador de oleodutos de petróleo"),
    OIL_OPERATOR(
        I, "Anexo I, 1.c)",
        "Produção, refinação, tratamento, armazenamento ou transporte de petróleo",
    ),
    OIL_CENTRAL_STOCKHOLDING(I, "Anexo I, 1.c)", "Entidade central de armazenagem de petróleo"),
    GAS_SUPPLIER(I, "Anexo I, 1.d)", "Comercialização de gás"),
    GAS_DISTRIBUTION_OPERATOR(I, "Anexo I, 1.d)", "Operador da rede de distribuição de gás"),
    GAS_TRANSMISSION_OPERATOR(I, "Anexo I, 1.d)", "Operador da rede de transporte de gás"),
    GAS_STORAGE_OPERATOR(I, "Anexo I, 1.d)", "Operador de armazenamento de gás"),
    LNG_OPERATOR(I, "Anexo I, 1.d)", "Operador da rede de gás natural liquefeito (GNL)"),
    NATURAL_GAS_UNDERTAKING(I, "Anexo I, 1.d)", "Empresa de gás natural"),
    GAS_REFINING_OPERATOR(I, "Anexo I, 1.d)", "Refinação e tratamento de gás natural"),
    HYDROGEN_OPERATOR(I, "Anexo I, 1.e)", "Produção, armazenamento ou transporte de hidrogénio"),

    // Annex I, 2. Transport
    AIR_CARRIER(I, "Anexo I, 2.a)", "Transportadora aérea comercial"),
    AIRPORT(
        I, "Anexo I, 2.a)",
        "Gestão de aeroporto ou exploração de instalações auxiliares dentro de aeroportos",
    ),
    AIR_TRAFFIC_CONTROL(I, "Anexo I, 2.a)", "Controlo de tráfego aéreo"),
    RAIL_INFRASTRUCTURE_MANAGER(I, "Anexo I, 2.b)", "Gestão de infraestrutura ferroviária"),
    RAILWAY_UNDERTAKING(I, "Anexo I, 2.b)", "Empresa ferroviária ou operador de instalações de serviço ferroviário"),
    WATER_TRANSPORT_COMPANY(
        I, "Anexo I, 2.c)",
        "Transporte de passageiros ou mercadorias por via marítima, costeira ou fluvial",
    ),
    PORT_MANAGING_BODY(I, "Anexo I, 2.c)", "Gestão de porto ou de instalações portuárias"),
    VESSEL_TRAFFIC_SERVICE(I, "Anexo I, 2.c)", "Serviço de tráfego marítimo (VTS)"),
    ROAD_TRAFFIC_AUTHORITY(I, "Anexo I, 2.d)", "Autoridade rodoviária responsável pela gestão do tráfego"),
    INTELLIGENT_TRANSPORT_SYSTEMS(I, "Anexo I, 2.d)", "Operador de sistemas de transporte inteligentes"),

    // Annex I, 3. to 7.
    CREDIT_INSTITUTION(I, "Anexo I, 3.", "Instituição de crédito (banco)"),
    TRADING_VENUE(I, "Anexo I, 4.", "Operador de plataforma de negociação"),
    CENTRAL_COUNTERPARTY(I, "Anexo I, 4.", "Contraparte central (CCP)"),
    HEALTHCARE_PROVIDER(I, "Anexo I, 5.", "Prestação de cuidados de saúde"),
    EU_REFERENCE_LABORATORY(I, "Anexo I, 5.", "Laboratório de referência da UE"),
    MEDICINAL_PRODUCT_RESEARCH(I, "Anexo I, 5.", "Investigação e desenvolvimento de medicamentos"),
    DRINKING_WATER(
        I, "Anexo I, 6.",
        "Fornecimento ou distribuição de água para consumo humano, como parte essencial da atividade",
    ),
    WASTE_WATER(
        I, "Anexo I, 7.",
        "Recolha, eliminação ou tratamento de águas residuais, como parte essencial da atividade",
    ),

    // Annex I, 8. Digital infrastructure
    INTERNET_EXCHANGE_POINT(I, "Anexo I, 8.", "Ponto de troca de tráfego (IXP)"),
    DNS_SERVICE_PROVIDER(I, "Anexo I, 8.", "Prestação de serviços de DNS"),
    TLD_NAME_REGISTRY(I, "Anexo I, 8.", "Registo de nomes de domínio de topo (TLD)"),
    CLOUD_COMPUTING_PROVIDER(I, "Anexo I, 8.", "Serviços de computação em nuvem"),
    DATA_CENTRE_PROVIDER(I, "Anexo I, 8.", "Serviços de centro de dados"),
    CONTENT_DELIVERY_NETWORK(I, "Anexo I, 8.", "Rede de distribuição de conteúdos (CDN)"),
    QUALIFIED_TRUST_SERVICE_PROVIDER(
        I, "Anexo I, 8.",
        "Serviços de confiança qualificados (por exemplo, assinatura eletrónica qualificada)",
    ),
    TRUST_SERVICE_PROVIDER(I, "Anexo I, 8.", "Serviços de confiança não qualificados"),
    PUBLIC_ELECTRONIC_COMMUNICATIONS_NETWORK(I, "Anexo I, 8.", "Rede pública de comunicações eletrónicas"),
    PUBLIC_ELECTRONIC_COMMUNICATIONS_SERVICE(
        I, "Anexo I, 8.",
        "Serviço de comunicações eletrónicas acessível ao público (telefone, internet)",
    ),

    // Annex I, 9. ICT service management (business-to-business) and 10. Space
    MANAGED_SERVICE_PROVIDER(I, "Anexo I, 9.", "Serviços de TI geridos para outras empresas"),
    MANAGED_SECURITY_SERVICE_PROVIDER(I, "Anexo I, 9.", "Serviços de segurança geridos para outras empresas"),
    SPACE_GROUND_INFRASTRUCTURE(
        I, "Anexo I, 10.",
        "Infraestruturas terrestres que apoiam serviços espaciais",
    ),

    // Annex II
    POSTAL_COURIER(II, "Anexo II, 1.", "Serviços postais ou de estafeta"),
    WASTE_MANAGEMENT(II, "Anexo II, 2.", "Gestão de resíduos como atividade principal"),
    CHEMICALS(II, "Anexo II, 3.", "Produção ou distribuição de produtos químicos"),
    FOOD(
        II, "Anexo II, 4.",
        "Distribuição por grosso, produção ou transformação industrial de produtos alimentares",
    ),
    MEDICAL_DEVICES(II, "Anexo II, 5.a)", "Fabrico de dispositivos médicos ou de diagnóstico in vitro"),
    ELECTRONICS_MANUFACTURING(
        II, "Anexo II, 5.b)",
        "Fabrico de equipamentos informáticos, de comunicação, eletrónicos ou óticos", "26",
    ),
    ELECTRICAL_EQUIPMENT_MANUFACTURING(II, "Anexo II, 5.c)", "Fabrico de equipamento elétrico", "27"),
    MACHINERY_MANUFACTURING(II, "Anexo II, 5.d)", "Fabrico de máquinas e equipamentos", "28"),
    MOTOR_VEHICLE_MANUFACTURING(
        II, "Anexo II, 5.e)",
        "Fabrico de veículos automóveis, reboques e semirreboques", "29",
    ),
    OTHER_TRANSPORT_EQUIPMENT_MANUFACTURING(II, "Anexo II, 5.f)", "Fabrico de outro equipamento de transporte", "30"),
    ONLINE_MARKETPLACE(II, "Anexo II, 6.", "Mercado em linha (marketplace)"),
    ONLINE_SEARCH_ENGINE(II, "Anexo II, 6.", "Motor de pesquisa em linha"),
    SOCIAL_NETWORK_PLATFORM(II, "Anexo II, 6.", "Plataforma de redes sociais"),
    RESEARCH_ORGANISATION(II, "Anexo II, 7.", "Organismo de investigação"),
    ;

    companion object {
        /**
         * Types the CAE code suggests. Only the annex rows that name a NACE Rev. 2 division (annex II, 5.b to 5.f)
         * can be derived from the CAE: every other type is defined by what the entity does, so the user picks it.
         * The first two digits of the CAE are the NACE division.
         */
        fun suggestedFor(cae: String): Set<Nis2EntityType> {
            val division = cae.trim().take(2)
            return entries.filterTo(mutableSetOf()) { it.naceDivision == division }
        }
    }
}
