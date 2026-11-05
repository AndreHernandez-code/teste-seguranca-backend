-- =============================================================================
-- V1 - schema inicial do sistema de lava jato.
-- =============================================================================
--
-- Esta migracao descreve o schema que existia ate a Etapa 6, quando quem o
-- criava era o Hibernate com ddl-auto=update. A partir daqui, o banco e
-- construido por migracao versionada e o Hibernate so confere (validate).
--
-- REGRAS QUE VALEM PARA TODA MIGRACAO DESTA PASTA
--
--  1. Nome: V<numero>__<descricao>.sql. O numero define a ORDEM e nao pode ser
--     reaproveitado. Uma migracao ja aplicada em algum banco NUNCA e editada -
--     o Flyway guarda o checksum dela e recusa subir se o arquivo mudar. Para
--     corrigir, cria-se a proxima.
--
--  2. Um arquivo, uma intencao. Nao juntar "cria tabela" com "corrige dado":
--     se der errado, o rollback e por migracao.
--
--  3. IF NOT EXISTS neste V1 de proposito: a base de desenvolvimento ja tem
--     estas tabelas, criadas pelo Hibernate. Sem o IF NOT EXISTS, aplicar o V1
--     numa base existente quebraria com "relation already exists". Como o
--     flyway_schema_history comeca vazio, o Flyway tentaria criar tudo de novo.
--     Em base nova (producao) o efeito e o mesmo: cria.
--
--  4. As colunas de FK tem indice explicito. O Postgres NAO cria indice
--     automatico para chave estrangeira (so para PK e UNIQUE), e sem ele toda
--     consulta que filtre por veiculo_id ou cliente_id faz varredura completa.

CREATE TABLE IF NOT EXISTS cliente (
    id        BIGSERIAL PRIMARY KEY,
    nome      VARCHAR(255),
    telefone  VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS servico (
    id               BIGSERIAL PRIMARY KEY,
    nome             VARCHAR(255),
    preco            NUMERIC(38, 2),
    tempo_estimado   INTEGER
);

CREATE TABLE IF NOT EXISTS veiculo (
    id          BIGSERIAL PRIMARY KEY,
    placa       VARCHAR(255),
    modelo      VARCHAR(255),
    cliente_id  BIGINT REFERENCES cliente (id)
);

CREATE TABLE IF NOT EXISTS agendamento (
    id             BIGSERIAL PRIMARY KEY,
    data_hora      TIMESTAMP(6),
    status         VARCHAR(255),
    preco_cobrado  NUMERIC(38, 2),
    veiculo_id     BIGINT REFERENCES veiculo (id),
    servico_id     BIGINT REFERENCES servico (id)
);

-- usuario e a unica tabela com nome reservado explicito (@Table(name="usuario")),
-- porque "user" e palavra reservada em SQL.
CREATE TABLE IF NOT EXISTS usuario (
    id      BIGSERIAL PRIMARY KEY,
    login   VARCHAR(60)  NOT NULL,
    senha   VARCHAR(100) NOT NULL,
    nome    VARCHAR(120) NOT NULL,
    perfil  VARCHAR(20)  NOT NULL,
    ativo   BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_usuario_login UNIQUE (login)
);

-- Indices das chaves estrangeiras (ver regra 4 acima).
CREATE INDEX IF NOT EXISTS idx_veiculo_cliente       ON veiculo (cliente_id);
CREATE INDEX IF NOT EXISTS idx_agendamento_veiculo   ON agendamento (veiculo_id);
CREATE INDEX IF NOT EXISTS idx_agendamento_servico   ON agendamento (servico_id);

-- Indice para a consulta mais comum de agendamento: a agenda de um dia.
CREATE INDEX IF NOT EXISTS idx_agendamento_data_hora ON agendamento (data_hora);
