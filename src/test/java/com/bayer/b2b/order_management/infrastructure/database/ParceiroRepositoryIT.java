package com.bayer.b2b.order_management.infrastructure.database;

import com.bayer.b2b.order_management.domain.model.Parceiro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de Testes de Integração para a interface ParceiroRepository.
 * <p>
 * O foco desta classe é atestar o mapeamento Objeto-Relacional (ORM),
 * o comportamento das restrições (Constraints) do banco de dados e a
 * validade sintática das consultas customizadas (HQL) com Lock Pessimista.
 * </p>
 * <p>
 * Utiliza @DataJpaTest para subir uma fatia (slice) do contexto do Spring
 * contendo apenas o ecossistema de persistência, executando cada teste
 * dentro de uma transação isolada que sofre rollback automático ao final.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@DataJpaTest
@DisplayName("Integração: ParceiroRepository")
class ParceiroRepositoryIT {

    @Autowired
    private ParceiroRepository parceiroRepository;

    /**
     * O TestEntityManager é uma ferramenta vital para testes de nível corporativo.
     * Ele nos permite manipular manualmente o contexto de persistência (flush, clear),
     * evitando os falsos positivos causados pelo Cache de Primeiro Nível do Hibernate.
     */
    @Autowired
    private TestEntityManager entityManager;

    @Nested
    @DisplayName("1. Persistência Base e Recuperação")
    class PersistenciaBaseTests {

        @Test
        @DisplayName("Deve persistir um novo parceiro e gerar o ID via autoincremento (GenerationType.IDENTITY)")
        void devePersistirERecuperarParceiro() {
            // Arrange
            Parceiro parceiro = new Parceiro("Bayer Agro", "11111111111111", new BigDecimal("75000.00"));

            // Act
            Parceiro salvo = parceiroRepository.save(parceiro);

            // O flush força o INSERT real no banco, e o clear apaga a memória do Hibernate.
            entityManager.flush();
            entityManager.clear();

            // Ao buscar agora, garantimos que a query bateu fisicamente no banco de dados.
            Optional<Parceiro> parceiroRecuperado = parceiroRepository.findById(salvo.getId());

            // Assert
            assertTrue(parceiroRecuperado.isPresent(), "O parceiro deve ser encontrado no banco de dados");
            assertNotNull(parceiroRecuperado.get().getId(), "O banco de dados deve ter gerado a Primary Key (ID)");
            assertEquals("Bayer Agro", parceiroRecuperado.get().getNome());
            assertEquals("11111111111111", parceiroRecuperado.get().getCnpj());

            // Comparações de BigDecimal vindos do banco devem usar compareTo para ignorar diferenças de escala (ex: 75000.00 vs 75000.000)
            assertEquals(0, new BigDecimal("75000.00").compareTo(parceiroRecuperado.get().getLimiteCredito()));
        }
    }

    @Nested
    @DisplayName("2. Integridade de Dados (Constraints de Banco)")
    class DataIntegrityTests {

        @Test
        @DisplayName("Deve lançar DataIntegrityViolationException (Unique Constraint) ao tentar salvar CNPJs duplicados")
        void deveFalharAoSalvarCnpjDuplicado() {
            // Arrange
            String cnpjUnico = "99999999999999";
            Parceiro parceiro1 = new Parceiro("Bayer Sul", cnpjUnico, new BigDecimal("1000.00"));
            Parceiro parceiro2_Duplicado = new Parceiro("Bayer Norte", cnpjUnico, new BigDecimal("2000.00"));

            // Salvamos o primeiro perfeitamente
            parceiroRepository.save(parceiro1);
            entityManager.flush(); // Consolida no banco

            // Act & Assert
            // Tentamos salvar o segundo e capturamos a restrição do banco de dados relacional.
            assertThrows(DataIntegrityViolationException.class, () -> {
                parceiroRepository.save(parceiro2_Duplicado);
                // Sem este flush explícito, o Hibernate guardaria o INSERT na memória até o fim da transação,
                // e a exceção não seria pega dentro deste bloco assertThrows.
                entityManager.flush();
            }, "O banco de dados deve rejeitar o registro devido à constraint UNIQUE no campo CNPJ");
        }
    }

    @Nested
    @DisplayName("3. Engenharia de Alta Escala: Locks de Concorrência")
    class ConcurrencyLockTests {

        @Test
        @DisplayName("Deve executar a query customizada de PESSIMISTIC_WRITE sem erros de sintaxe (HQL) e com mapeamento correto")
        void deveExecutarQueryComBloqueioPessimista() {
            // Arrange
            Parceiro parceiro = new Parceiro("Bayer Labs", "55555555555555", new BigDecimal("150000.00"));
            Parceiro salvo = parceiroRepository.save(parceiro);

            entityManager.flush();
            entityManager.clear(); // Crucial: Remove da memória para forçar o SELECT FOR UPDATE no banco real

            // Act
            // Esta chamada atesta que a query "@Query("SELECT p FROM Parceiro p WHERE p.id = :id")"
            // combinada com "@Lock(LockModeType.PESSIMISTIC_WRITE)" está sintaticamente perfeita e o Dialeto do DB suporta a operação.
            Optional<Parceiro> lockado = parceiroRepository.findByIdWithPessimisticLock(salvo.getId());

            // Assert
            assertTrue(lockado.isPresent(), "Deve recuperar o parceiro sob bloqueio");
            assertEquals(salvo.getId(), lockado.get().getId());
        }
    }
}