# TechMaint Solutions

Sistema web para gerenciamento de solicitações de manutenção técnica, com fluxo completo para clientes e funcionários. A aplicação permite cadastrar clientes, abrir solicitações, criar orçamentos, aprovar ou rejeitar serviços, registrar manutenções, confirmar pagamentos, finalizar atendimentos e acompanhar relatórios de receita.

## Visão geral

O projeto é dividido em duas aplicações:

- `backend`: API REST desenvolvida com Spring Boot.
- `frontend`: interface web desenvolvida com Angular.

## Funcionalidades

- Autenticação de clientes e funcionários.
- Cadastro de clientes, funcionários e categorias de equipamento.
- Abertura e acompanhamento de solicitações de manutenção.
- Fluxo de orçamento, aprovação, rejeição e resgate de serviço.
- Registro, redirecionamento e finalização de manutenções.
- Confirmação de pagamento e controle de divergência de valores.
- Histórico de mudanças de status da solicitação.
- Relatórios de receitas por período, por categoria e visão geral.
- Geração de relatórios em PDF.
- Documentação da API via Swagger/OpenAPI.

## Tecnologias

### Backend

- Java 21
- Spring Boot 3.5
- Spring Web
- Spring Data JPA
- Spring Validation
- Spring Mail
- MySQL
- Lombok
- iText PDF
- Springdoc OpenAPI

### Frontend

- Angular 21
- TypeScript
- Bootstrap 5
- Bootstrap Icons
- RxJS
- ngx-mask
- jsPDF

## Pré-requisitos

- Java 21+
- Node.js e npm
- MySQL 8+
- Maven Wrapper, já incluído em `backend`

## Configuração do banco de dados

Crie o banco MySQL usando o script disponível em:

```text
backend/src/main/resources/bd/web2_bd
```

Por padrão, a API usa as seguintes configurações em `backend/src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/web2_bd
spring.datasource.username=root
spring.datasource.password=
```

Se necessário, ajuste usuário, senha, porta ou nome do banco conforme o seu ambiente local.

## Como executar

### 1. Instale as dependências do frontend

Na raiz do projeto:

```bash
npm install
```

### 2. Inicie o backend

No Windows:

```bash
cd backend
.\mvnw.cmd spring-boot:run
```

No Linux/macOS:

```bash
cd backend
./mvnw spring-boot:run
```

A API ficará disponível em:

```text
http://localhost:8080
```

### 3. Inicie o frontend

Em outro terminal:

```bash
cd frontend
npx ng serve
```

A aplicação ficará disponível em:

```text
http://localhost:4200
```

## Documentação da API

Com o backend em execução, acesse:

```text
http://localhost:8080/swagger-ui.html
```

ou:

```text
http://localhost:8080/swagger-ui/index.html
```

## Estrutura do projeto

```text
TechMaint-Solutions/
+-- backend/
|   +-- src/main/java/com/trabalhow2/backend/
|   |   +-- config/
|   |   +-- controller/
|   |   +-- exception/
|   |   +-- model/
|   |   +-- repository/
|   |   +-- service/
|   +-- src/main/resources/
+-- frontend/
|   +-- public/
|   +-- src/app/
|       +-- guards/
|       +-- interceptors/
|       +-- pages/
|       +-- services/
|       +-- shared/
+-- package.json
+-- README.md
```

## Rotas principais da API

- `POST /login`: autenticação.
- `POST /login/logout`: encerramento de sessão.
- `GET /login/sessao`: consulta da sessão atual.
- `/clientes`: cadastro e manutenção de clientes.
- `/funcionarios`: cadastro e manutenção de funcionários.
- `/categorias`: cadastro e manutenção de categorias.
- `/solicitacoes`: fluxo de solicitações, orçamentos, pagamentos e manutenções.
- `/relatorios/receitas`: relatórios de receita e geração de PDFs.

## Scripts úteis

### Frontend

Executar em `frontend`:

```bash
npx ng serve
npx ng build
npx ng test
```

### Backend

Executar em `backend`:

```bash
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
```

Em Linux/macOS, substitua `.\mvnw.cmd` por `./mvnw`.

## Observações

- O frontend está configurado para consumir a API em `http://localhost:8080`.
- O backend utiliza sessão/cookies e o frontend envia credenciais nas chamadas para a API.
- Configure credenciais de e-mail e banco de dados conforme o ambiente antes de executar em produção.

## Licença

Este projeto foi desenvolvido para fins acadêmicos.
