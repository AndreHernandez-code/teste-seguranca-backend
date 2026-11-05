# Lava-Jato — pacote de teste de invasão

Este pacote sobe um sistema de lava-jato completo para você tentar invadir.
Você não precisa saber programar para ligá-lo.

---

## 1. O que instalar antes

São só duas coisas. **Instale as duas antes de tentar rodar.**

### Java 25 (JDK)

O sistema é escrito em Java e precisa compilá-lo na sua máquina.

1. Baixe: <https://adoptium.net/temurin/releases/?version=25>
2. Escolha **Windows**, **x64**, **JDK**, formato **.msi**
3. Ao instalar, **marque as duas caixas**:
   - `Set JAVA_HOME variable`
   - `Add to PATH`

> Precisa ser o **JDK**, não o JRE. O JRE só executa programas; o JDK compila.

### PostgreSQL

É o banco de dados onde o sistema guarda tudo.

1. Baixe: <https://www.postgresql.org/download/windows/>
2. Rode o instalador e aceite as opções padrão
3. **Na tela da senha**, defina uma senha para o usuário `postgres`
   - **Anote essa senha.** Você vai digitá-la toda vez que ligar o sistema
4. Deixe marcado o componente **Command Line Tools**

---

## 2. Como ligar

Dê **dois cliques** em:

```
INICIAR.bat
```

Vai abrir uma janela preta. Ela faz tudo sozinha:

- Confere se o Java e o PostgreSQL estão instalados
- Pede a senha do `postgres` que você definiu
- **Cria um banco novo e vazio só para este teste** (não encosta em nada seu)
- Sobe o sistema
- Abre o navegador em `http://localhost:8080`

Na primeira vez demora alguns minutos (ele baixa bibliotecas). Depois é rápido.

### Login

| Campo | Valor |
|---|---|
| Usuário | `admin` |
| Senha | `TesteDeInvasao2026` |

### Para desligar

Feche a janela preta, ou aperte `Ctrl+C` nela.

---

## 3. O banco de teste

O sistema cria sozinho um banco chamado **`lavajato_hack`**.

Esse banco é **descartável e seu**. Pode apagar, corromper, encher de dados
falsos — é para isso que ele existe. Nada do que você fizer ali quebra o
computador nem afeta outros programas.

Se quiser começar do zero, apague o banco `lavajato_hack` pelo pgAdmin e rode
o `INICIAR.bat` de novo.

---

## 4. Arquivos que interessam

Se você for ler o código, o que importa está aqui:

```
backend/                          servidor (Java / Spring Boot)
  src/main/java/br/com/lavajato/
    controller/                   os endpoints da API
    security/                     login, sessão, permissões
    service/                      regras de negócio
  src/main/resources/
    application.properties        configuração
    db/migration/                 o SQL que cria as tabelas

frontend/                         a tela (HTML, CSS, JS puro)
```

Você pode editar o código à vontade. **Mas edite `frontend/`**, e não
`backend/src/main/resources/static/` — veja a nota abaixo.

---

## 5. Coisas que vale saber antes de mexer

**O front é copiado.** O servidor serve a tela de
`backend/src/main/resources/static/`, que é uma **cópia** de `frontend/`. Se
editar só uma das duas, sua mudança pode não aparecer. Para sincronizar:

```bash
cp frontend/index.html frontend/app.js frontend/styles.css \
   backend/src/main/resources/static/
```

Depois reinicie o `INICIAR.bat`.

**Porta 8080.** Se já estiver em uso, o `INICIAR.bat` avisa e para. Descubra
quem está usando com:

```
netstat -ano | findstr :8080
```

**Perfil `dev`, não `prod`.** O teste roda em **HTTP** de propósito, na sua
máquina, para não precisar de certificado. O perfil de produção exige HTTPS e
não é o que está ativo aqui. Se você for testar supondo que este sistema está
"em produção", lembre que **HTTPS, firewall e backup não fazem parte deste
pacote** — eles são coisas do servidor de verdade, não do código.

**O `INICIAR.bat` não instala nada.** Ele só confere e avisa o que falta.

---

## 6. O que testar

Veja **O-QUE-TESTAR.md**, nesta mesma pasta — tem uma lista do que faz sentido
tentar.

---

## 7. Se der erro

O próprio `INICIAR.bat` explica as quatro causas mais comuns e como resolver
cada uma. As mensagens aparecem no fim da janela preta.

Se o erro for outro, copie **toda a saída da janela** e mande para quem te
passou o projeto. A saída tem a informação que precisa.
