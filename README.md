# B2B Order Management System 🚀

Este projeto é um microsserviço de Gestão de Pedidos B2B, construído para atuar como o coração transacional de uma operação corporativa. Ele foi desenhado sob os preceitos do **Domain-Driven Design (DDD)** e da **Clean Architecture (Hexagonal)**, focado em alta concorrência, consistência financeira e resiliência em falhas.

## 🎯 O Desafio vs. A Solução

A tabela abaixo resume como os requisitos arquiteturais foram abordados com padrões da indústria (Nível Netflix/Shopify).

| Requisito Exigido | Solução Implementada | Benefício Arquitetural |
| :--- | :--- | :--- |
| Alta Concorrência (Gerir Saldo) | **Pessimistic Locking (PostgreSQL)** | Quando milhares de requisições tentam usar o limite de um mesmo cliente simultaneamente, o `FOR UPDATE` do banco garante enfileiramento transacional ACID. Nenhuma venda "fantasma" é gerada. |
| Orquestração de Pedidos B2B | **Rich Domain Model (DDD)** | O `Pedido` é um *Aggregate Root* e o `ItemPedido` encapsula sua própria matemática financeira. Blindamos o sistema com *Fail-Fast*, impedindo que a memória seja fraudada antes mesmo de tocar no banco. |
| Status e Cancelamentos | **Padrão State Machine (Enum)** | As regras de fluxo logístico não estão em "IFs" perdidos. O domínio decide se um pedido PENDENTE pode ser ENTREGUE (rejeitando rotas ilegais nativamente). |
| Integração de Mensageria | **Apache Kafka (Event-Driven)** | Disparo de eventos assíncronos não-bloqueantes. A Thread HTTP principal é liberada rapidamente, garantindo altíssimo *throughput* na API REST. |
| Escalabilidade e Resiliência | **MongoDB (Dead Letter Queue - DLQ)** | Se o broker do Kafka cair, o sistema **não perde a venda** e nem dá rollback no PostgreSQL. A falha é engolida assincronamente e o evento é armazenado na DLQ do Mongo para futuro *Retry* pelo time de SRE. |

---

## 🛠️ Stack Tecnológica

*   **Java 21** (ou 17+ compatível)
*   **Spring Boot 4.1.x / 3.x** (Web, Data JPA, Data MongoDB, Kafka, Validation, Actuator)
*   **PostgreSQL 16** (Banco Transacional Primário)
*   **MongoDB 7** (Armazenamento NoSQL de Contingência / DLQ)
*   **Apache Kafka 3.7 (KRaft)** (Mensageria e Integração Logística)
*   **Docker & Docker Compose** (Orquestração de Infraestrutura)
*   **JUnit 5, Mockito & AssertJ** (Testes Unitários e de Integração *Gold Class*)
*   **Swagger / OpenAPI 3** (Documentação Contratual)

---

## ⚙️ Como Executar o Projeto

O ambiente foi completamente containerizado para proporcionar uma experiência *"One-Click Run"*.

### Pré-requisitos
* Docker e Docker Compose (v2)

Não é necessário ter Java ou Maven instalados: o build do jar acontece dentro da imagem Docker.

### Executar tudo com um único comando
Na raiz do projeto (onde está o `docker-compose.yml`):

```bash
docker compose up --build
```

O comando sobe PostgreSQL, MongoDB, Kafka e a aplicação, nessa ordem. A aplicação só inicia depois que os três ficam *healthy*. Ela fica disponível em `http://localhost:8080`, e a saúde pode ser conferida em `http://localhost:8080/actuator/health`.

Para parar e remover os containers:

```bash
docker compose down
```

### Rodar a aplicação fora do Docker (desenvolvimento)
Requer JDK 21. Suba apenas a infraestrutura e rode a aplicação pelo Maven Wrapper:

```bash
docker compose up -d postgres mongodb kafka
./mvnw spring-boot:run
```

Nesse modo a aplicação usa as portas expostas no host, configuradas no `application.yaml`: Postgres em `5435`, Mongo em `27017` e Kafka em `9094`.

### Testes

```bash
./mvnw test
```

### 📖 Documentação da API (Swagger)

A API foi documentada utilizando OpenAPI 3. Com a aplicação rodando, acesse a interface interativa do Swagger UI diretamente no seu navegador:

🔗 http://localhost:8080/swagger-ui/index.html

### 🔗 Endpoints Principais

A API é estritamente RESTful. Erros de negócio retornam HTTP 422 Unprocessable Entity com um formato JSON padronizado pelo nosso GlobalExceptionHandler, blindando as Stacktraces do backend.

### Parceiros (Gestão Financeira)
POST /api/v1/parceiros - Cadastra um novo cliente corporativo e injeta seu limite de crédito inicial.

### Pedidos (Core Transacional)
POST /api/v1/pedidos - Submete a intenção de compra. Aciona a trava de concorrência, debita o limite do cliente parceiro, salva a estrutura em cascata e emite o evento PedidoStatusChangedEvent no Kafka.

### 🛡️ Qualidade de Software e Testes
A arquitetura de testes foi desenhada para atingir os mais altos padrões do mercado (Branch Coverage e Prevenção de Fraudes).

Isolamento de Domínio: As regras matemáticas de preço, a proteção de coleções Collections.unmodifiableSet() e a Máquina de Estados foram testadas via JUnit sem subida de contexto do Spring.

Testes de Integração @WebMvcTest: A serialização dos DTOs e a captura dos erros 422 pelo ControllerAdvice foram validados isolando o UseCase via Mockito.

Mitigação do N+1: Os testes do PedidoRepository (via @DataJpaTest) atestam que a sintaxe HQL customizada com JOIN FETCH consolida a leitura dos dados mitigando problemas de Lazy Loading em produção.

Testes de Resiliência (SRE): O KafkaOrderEventPublisherTest comprova que a aplicação não trava e desvia perfeitamente o fluxo para a DLQ (MongoRepository) quando ocorre um erro forçado de conexão com o broker de mensageria.

Para executar a bateria de testes de nível ouro:

```bash
./mvnw clean test
```