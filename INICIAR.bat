@echo off
REM ===========================================================================
REM  Lava-Jato - inicializador para teste de invasao
REM ===========================================================================
REM
REM  O QUE ESTE ARQUIVO FAZ
REM  ---------------------------------------------------------------------------
REM  Sobe o sistema inteiro com dois cliques, para quem nao conhece o projeto.
REM  Ele confere o que falta, cria um banco NOVO E VAZIO so para este teste, e
REM  abre o navegador. Nao toca em banco que ja exista na maquina.
REM
REM  POR QUE ELE CRIA UM BANCO PROPRIO
REM  ---------------------------------------------------------------------------
REM  Se apontasse para um banco qualquer da maquina, o teste poderia destruir
REM  dado real de outra pessoa. O banco daqui se chama lavajato_hack e e
REM  descartavel: pode apagar, corromper, encher de registro falso - e para isso
REM  que ele existe.
REM
REM  O QUE ELE *NAO* FAZ
REM  ---------------------------------------------------------------------------
REM  - Nao instala Java nem PostgreSQL. So avisa com clareza o que falta e
REM    onde baixar. Instalar exige administrador e nao da para automatizar aqui.
REM  - Nao usa o perfil 'prod': aquele perfil liga o cookie Secure, que exige
REM    HTTPS. Sem certificado, o login pararia de funcionar no navegador.
REM    Este teste roda em HTTP na propria maquina, de proposito.
REM
REM  PARA ENCERRAR: feche esta janela, ou aperte Ctrl+C nela.
REM ===========================================================================

setlocal enabledelayedexpansion
title Lava-Jato - servidor de teste

REM Raiz do projeto = a pasta onde este .bat esta.
set "RAIZ=%~dp0"
set "BACKEND=%RAIZ%backend"

REM ---- Banco de teste: nome e credenciais deste ambiente descartavel ----
REM A porta 5433 e usada se a 5432 estiver ocupada por outro PostgreSQL.
set "DB_NOME=lavajato_hack"
set "DB_HOST=localhost"
set "DB_PORTA=5432"
set "DB_ADMIN=postgres"

echo.
echo ============================================================
echo   LAVA-JATO - INICIALIZADOR
echo ============================================================
echo.
echo   Este atalho sobe o sistema para teste de invasao.
echo   Nada aqui e dado real: o banco criado e novo e vazio.
echo.
echo ------------------------------------------------------------
echo.

REM ===========================================================================
REM  PASSO 1 - Conferir o Java
REM ===========================================================================
echo [1/4] Conferindo o Java...

where java >nul 2>&1
if errorlevel 1 goto SEM_JAVA

REM Ler a versao. O "java -version" escreve em stderr, por isso o 2^>^&1.
set "VER_JAVA="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined VER_JAVA set "VER_JAVA=%%~v"
)

if not defined VER_JAVA goto SEM_JAVA

REM A versao vem como "25.0.4.1". O primeiro campo antes do ponto e o major.
for /f "tokens=1 delims=." %%m in ("!VER_JAVA!") do set "JAVA_MAJOR=%%m"

REM Java antigo escreve "1.8.0_392": o major de verdade e o segundo campo.
if "!JAVA_MAJOR!"=="1" (
    for /f "tokens=2 delims=." %%m in ("!VER_JAVA!") do set "JAVA_MAJOR=%%m"
)

if !JAVA_MAJOR! LSS 25 goto JAVA_ANTIGO

echo       Java !VER_JAVA! encontrado. OK.
echo.

REM ===========================================================================
REM  PASSO 2 - Achar o PostgreSQL
REM ===========================================================================
echo [2/4] Procurando o PostgreSQL...

set "PG_BIN="

REM 2a. Primeiro tenta o PATH (funciona quando o instalador marcou a opcao).
for /f "delims=" %%p in ('where psql 2^>nul') do (
    if not defined PG_BIN (
        for %%d in ("%%~dp") do set "PG_BIN=%%~fd"
    )
)

REM 2b. Se nao achou, procura na pasta padrao de instalacao do Windows.
REM     O "for /d" pegou a versao mais alta por causa da ordenacao decrescente.
if not defined PG_BIN (
    for /f "delims=" %%d in ('dir /b /ad /o-n "C:\Program Files\PostgreSQL" 2^>nul') do (
        if not defined PG_BIN (
            if exist "C:\Program Files\PostgreSQL\%%d\bin\psql.exe" (
                set "PG_BIN=C:\Program Files\PostgreSQL\%%d\bin"
            )
        )
    )
)

if not defined PG_BIN goto SEM_POSTGRES

echo       Encontrado em: !PG_BIN!
echo.

REM ===========================================================================
REM  PASSO 3 - Criar o banco de teste (se ainda nao existir)
REM ===========================================================================
echo [3/4] Preparando o banco de teste...

REM Quem cria o banco NAO e este .bat, e sim o proprio Java (CriadorDeBanco),
REM ja com a senha em maos. Assim a senha do postgres e digitada UMA vez so, e
REM nao fica escrita na linha de comando nem no historico do prompt.
echo       O sistema cria o banco "!DB_NOME!" automaticamente, se ele
echo       ainda nao existir. Nao precisa abrir o pgAdmin nem o psql.
echo.
echo       A senha do usuario "postgres" sera pedida no proximo passo.
echo.

REM ===========================================================================
REM  PASSO 4 - Subir a aplicacao
REM ===========================================================================
echo [4/4] Subindo o sistema...
echo.
echo ------------------------------------------------------------
echo.

REM A senha do banco e pedida aqui, uma vez, e fica so nesta janela.
REM Nao vai para arquivo nenhum - este .bat nao grava nada em disco.
set "PGPASSWORD="
set /p "PGPASSWORD=Digite a senha do usuario 'postgres' (a que voce definiu ao instalar o PostgreSQL): "
if not defined PGPASSWORD goto SEM_SENHA

echo.
echo       Senha recebida. Iniciando o servidor...
echo       (na primeira vez o Maven baixa as dependencias e demora alguns minutos)
echo.

REM ---------------------------------------------------------------------------
REM  Montagem do ambiente da aplicacao
REM ---------------------------------------------------------------------------
REM
REM  DB_URL     : aponta para o banco descartavel deste teste.
REM  DB_USER/DB_PASSWORD : credencial do banco.
REM  ADMIN_LOGIN/ADMIN_SENHA : o admin inicial que o sistema cria sozinho.
REM
REM  ADMIN_SENHA precisa ter 12+ caracteres (regra do AdminInicialSeeder) e nao
REM  pode ser uma das senhas obvias que ele recusa. A daqui cumpre os dois.
REM
REM  app.security.conferir-ambiente=false : desliga a trava que exige o perfil
REM  'prod' quando ha credenciais no ambiente. Este teste roda em dev, de
REM  proposito, para poder usar HTTP e nao precisar de certificado.
REM ---------------------------------------------------------------------------

set "DB_URL=jdbc:postgresql://!DB_HOST!:!DB_PORTA!/!DB_NOME!"
set "DB_USER=!DB_ADMIN!"
set "DB_PASSWORD=!PGPASSWORD!"
set "ADMIN_LOGIN=admin"
set "ADMIN_SENHA=TesteDeInvasao2026"

REM Autoriza a aplicacao a CRIAR o banco quando ele ainda nao existir. Quem le
REM isto e o CriadorDeBanco, que roda antes do Flyway. Fora deste .bat a chave
REM fica desligada de proposito - ver o comentario dela no application.properties.
set "CRIAR_BANCO_SE_FALTAR=true"

cd /d "%BACKEND%"
if errorlevel 1 goto SEM_BACKEND

echo ============================================================
echo   ABRA O NAVEGADOR EM:  http://localhost:8080
echo ============================================================
echo.
echo   Login de teste:   admin
echo   Senha de teste:   TesteDeInvasao2026
echo.
echo   Para ENCERRAR o servidor: feche esta janela ou Ctrl+C.
echo.
echo ------------------------------------------------------------
echo.

REM Abre o navegador depois de alguns segundos, em paralelo, para nao travar a
REM subida do servidor enquanto a pagina ainda nao responde.
start "" /b cmd /c "timeout /t 25 /nobreak >nul & start "" http://localhost:8080"

REM -q tira o ruido do Maven; o log da aplicacao continua aparecendo.
REM
REM O caminho vai COMPLETO (%BACKEND%\mvnw.cmd) de proposito. O Windows nao
REM procura comandos na pasta atual, entao um "mvnw.cmd" sozinho falha com
REM "'mvnw.cmd' nao e reconhecido" - mesmo estando ali do lado. Foi medido: so
REM ".\mvnw.cmd" ou o caminho inteiro funcionam.
call "%BACKEND%\mvnw.cmd" -q spring-boot:run ^
    -Dspring-boot.run.jvmArguments="-Dapp.security.conferir-ambiente=false -Dapp.banco.criar-se-faltar=true"

set "CODIGO_SAIDA=%ERRORLEVEL%"

echo.
if not "%CODIGO_SAIDA%"=="0" (
    echo ============================================================
    echo   O SISTEMA PAROU. Codigo de saida: %CODIGO_SAIDA%
    echo ============================================================
    echo.
    echo   Causas mais comuns:
    echo.
    echo   1^) SENHA DO POSTGRES ERRADA
    echo      "FATAL: password authentication failed".
    echo      Rode este .bat de novo e digite a senha correta.
    echo.
    echo   2^) PORTA 8080 JA EM USO
    echo      Outro programa usa a 8080. Feche-o e rode de novo.
    echo      Para ver quem esta usando:
    echo         netstat -ano ^| findstr :8080
    echo.
    echo   3^) O POSTGRESQL NAO ESTA RODANDO
    echo      Abra "Servicos" do Windows e inicie o servico
    echo      "postgresql-x64-*".
    echo.
    echo   4^) ERRO DE COMPILACAO OU DEPENDENCIA
    echo      A mensagem exata aparece acima, no log do Maven.
    echo.
) else (
    echo Servidor encerrado normalmente.
)

echo.
echo Pressione qualquer tecla para fechar...
pause >nul
exit /b %CODIGO_SAIDA%

REM ===========================================================================
REM  MENSAGENS DE ERRO - cada uma diz o que fazer, nao so o que falta
REM ===========================================================================

:SEM_JAVA
echo.
echo ============================================================
echo   FALTA O JAVA
echo ============================================================
echo.
echo   Este sistema precisa do Java 25 instalado.
echo.
echo   COMO RESOLVER:
echo     1. Baixe em: https://adoptium.net/temurin/releases/?version=25
echo     2. Escolha "Windows / x64 / JDK / .msi"
echo     3. Instale marcando a opcao
echo        "Set JAVA_HOME variable" e "Add to PATH"
echo     4. FECHE esta janela e abra o .bat de novo
echo.
echo   Importante: nao serve so o "Java Runtime" (JRE).
echo   Precisa ser o JDK, que e o que compila o projeto.
echo.
pause
exit /b 1

:JAVA_ANTIGO
echo.
echo ============================================================
echo   JAVA DESATUALIZADO - versao !VER_JAVA!
echo ============================================================
echo.
echo   O projeto exige Java 25 ou mais novo.
echo   Voce tem a versao !JAVA_MAJOR!, que e antiga demais para compilar.
echo.
echo   COMO RESOLVER:
echo     1. Baixe o Java 25 em:
echo        https://adoptium.net/temurin/releases/?version=25
echo     2. Instale (pode ficar junto com a versao antiga).
echo     3. FECHE esta janela e abra o .bat de novo.
echo.
echo   Se voce ja instalou e continua caindo aqui, o PATH do Windows
echo   esta apontando para a versao antiga. Nesse caso, ajuste a ordem
echo   em "Variaveis de Ambiente" ou remova a versao antiga.
echo.
pause
exit /b 1

:SEM_POSTGRES
echo.
echo ============================================================
echo   FALTA O POSTGRESQL
echo ============================================================
echo.
echo   Este sistema guarda os dados em PostgreSQL, que nao foi
echo   encontrado nesta maquina.
echo.
echo   COMO RESOLVER:
echo     1. Baixe em: https://www.postgresql.org/download/windows/
echo     2. Rode o instalador e escolha as opcoes padrao.
echo     3. NA TELA DA SENHA: defina uma senha para o usuario
echo        "postgres" e ANOTE - vai precisar dela aqui.
echo     4. Deixe o componente "Command Line Tools" marcado.
echo     5. FECHE esta janela e abra o .bat de novo.
echo.
echo   Se voce instalou em outra pasta que nao seja
echo   "C:\Program Files\PostgreSQL", edite este .bat e ajuste
echo   a linha que procura o psql.exe.
echo.
pause
exit /b 1

:SEM_SENHA
echo.
echo ============================================================
echo   NENHUMA SENHA DIGITADA
echo ============================================================
echo.
echo   O servidor precisa da senha do usuario "postgres" para
echo   criar o banco de teste.
echo.
echo   Essa e a senha que voce definiu quando instalou o
echo   PostgreSQL. Se nao lembrar, reinstale o PostgreSQL ou
echo   redefina a senha pelo pgAdmin.
echo.
pause
exit /b 1

:SEM_BACKEND
echo.
echo ============================================================
echo   PASTA "backend" NAO ENCONTRADA
echo ============================================================
echo.
echo   Este .bat precisa ficar na RAIZ do projeto, junto da
echo   pasta "backend". Procurado em:
echo.
echo      %BACKEND%
echo.
echo   Se voce moveu os arquivos, coloque o .bat de volta na
echo   pasta que tem "backend" e "frontend" dentro.
echo.
pause
exit /b 1
