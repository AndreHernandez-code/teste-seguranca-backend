-- =============================================================================
-- Usuario de banco com privilegio minimo (Etapa 6).
-- =============================================================================
--
-- NAO e uma migracao: nao vai em db/migration. Migracao altera schema; isto
-- aqui e provisionamento de acesso, e roda UMA vez, como superusuario, na
-- instalacao - nao a cada subida da aplicacao.
--
-- POR QUE ISTO EXISTE
--
-- A aplicacao vinha usando o usuario "postgres" - o superusuario do banco. Com
-- ele, uma falha de SQL injection, um bug de consulta ou uma credencial vazada
-- da ao atacante o banco INTEIRO: pode apagar qualquer base do servidor, criar
-- usuario novo, ler tabela de outro sistema, desligar o Postgres.
--
-- Com um usuario proprio e sem privilegio, o mesmo ataque alcanca somente as
-- tabelas deste sistema - e nem isso, se o acesso for so o necessario.
--
-- O QUE ESTE USUARIO PODE E O QUE NAO PODE
--
--   PODE: SELECT/INSERT/UPDATE/DELETE nas tabelas do schema (o CRUD do app).
--   PODE: usar as sequences (BIGSERIAL depende delas para gerar id).
--   NAO PODE: criar ou apagar TABELA, SCHEMA ou BANCO.
--   NAO PODE: ler ou escrever em qualquer outra base do servidor.
--   NAO PODE: criar usuario ou papel, nem conceder privilegio.
--
-- E o DDL (criar/alterar tabela)? Fica com OUTRO usuario, usado SO na
-- migracao. A aplicacao, no dia a dia, nao precisa criar tabela - e por isso
-- nao deve poder. Ver a secao "Dois usuarios" no fim do arquivo.
--
-- A senha NAO esta aqui: este arquivo vai para o repositorio. Troque
-- ':SENHA_AQUI' na hora de rodar, ou passe por variavel de ambiente no psql
-- (recomendado), e guarde a senha no gerenciador de segredos.

-- -----------------------------------------------------------------------------
-- 1. O papel. NOLOGIN primeiro: o papel so vira usuario de login de verdade
--    depois de receber senha, o que deixa claro que ele nasce sem acesso.
-- -----------------------------------------------------------------------------
CREATE ROLE lavajato_app WITH
    LOGIN
    NOSUPERUSER       -- o essencial: nao e superusuario
    NOCREATEDB        -- nao cria banco
    NOCREATEROLE      -- nao cria usuario nem concede privilegio a outros
    NOINHERIT         -- nao herda privilegio de outro papel por padrao
    NOREPLICATION     -- nao participa de replicacao
    NOBYPASSRLS       -- nao ignora row level security
    CONNECTION LIMIT 20;   -- teto de conexoes: evita um vazamento esgotar o pool

ALTER ROLE lavajato_app PASSWORD ':SENHA_AQUI';

-- -----------------------------------------------------------------------------
-- 2. Acesso ao banco e ao schema.
--    Sem GRANT USAGE no schema, nem SELECT em tabela funciona - o USAGE e o
--    que permite "enxergar" o schema.
-- -----------------------------------------------------------------------------
GRANT CONNECT ON DATABASE lavajato TO lavajato_app;
GRANT USAGE  ON SCHEMA public          TO lavajato_app;

-- -----------------------------------------------------------------------------
-- 3. CRUD nas tabelas.
--    Feito em DOIS passos: o que existe agora, e o padrao para o que vier.
--
--    O passo (b) e o que costuma faltar: sem ALTER DEFAULT PRIVILEGES, toda
--    tabela criada por uma migracao futura nasceria SEM acesso para o app, e o
--    erro so apareceria no primeiro acesso a ela, em producao.
-- -----------------------------------------------------------------------------
-- (a) tabelas e sequences de hoje
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES    IN SCHEMA public TO lavajato_app;
GRANT USAGE, SELECT                  ON ALL SEQUENCES IN SCHEMA public TO lavajato_app;

-- (b) o padrao para o futuro. FOR ROLE precisa ser o usuario que CRIA as
--     tabelas - o dono da migracao (ver "Dois usuarios" abaixo). Se a migracao
--     rodar como "postgres", e "postgres" que entra aqui.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lavajato_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO lavajato_app;

-- -----------------------------------------------------------------------------
-- 4. Fechar o que estava aberto por padrao.
--
--    Por padrao o Postgres da PUBLIC o direito de criar objeto em QUALQUER
--    schema, inclusive no public. Isso significa que o proprio app, mesmo sem
--    privilegio de DDL, poderia criar tabela - e um atacante com essa
--    credencial poderia plantar uma tabela ou uma funcao.
--
--    Revogar PUBLIC nao afeta o dono do schema nem o superusuario.
-- -----------------------------------------------------------------------------
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL    ON DATABASE lavajato FROM PUBLIC;

-- =============================================================================
-- DOIS USUARIOS: por que o app nao deve ser dono do schema
-- =============================================================================
--
-- O dono de uma tabela pode ALTER e DROP ela, e pode conceder privilegio sobre
-- ela. Se o usuario da aplicacao fosse o dono, ele poderia apagar a propria
-- tabela - e a separacao de privilegio nao teria servido de nada.
--
-- Por isso o desenho recomendado e:
--
--   lavajato_migra  - dono do schema. Usado SO pelo Flyway, na subida.
--                     Precisa de CREATE/ALTER/DROP.
--   lavajato_app    - o que esta acima. Usado pelo resto da aplicacao.
--                     Sem nenhum privilegio de DDL.
--
-- O Spring Boot suporta isso nativamente: as propriedades
-- spring.flyway.user / spring.flyway.password usam uma credencial DIFERENTE
-- da do datasource principal. Ficam de fora do application.properties (sao
-- segredo) e vem do ambiente, como DB_PASSWORD.
--
-- Para criar o usuario de migracao, repita o passo 1 com o nome lavajato_migra
-- e acrescente:
--
--   GRANT ALL ON SCHEMA public TO lavajato_migra;
--   ALTER SCHEMA public OWNER TO lavajato_migra;
--
-- E ajuste o ALTER DEFAULT PRIVILEGES acima para FOR ROLE lavajato_migra.
--
-- Nao esta configurado assim por padrao no projeto de proposito: exige duas
-- credenciais guardadas e um passo de instalacao a mais. Fica como o proximo
-- degrau, e o passo 3(b) ja esta escrito de um jeito que aceita a troca.
