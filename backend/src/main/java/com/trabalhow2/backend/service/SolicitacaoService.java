package com.trabalhow2.backend.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.trabalhow2.backend.controller.request.AbrirSolicitacaoRequest;
import com.trabalhow2.backend.controller.request.ConfirmarPagamentoRequest;
import com.trabalhow2.backend.controller.request.EfetuarOrcamentoRequest;
import com.trabalhow2.backend.controller.request.ItemOrcamentoRequest;
import com.trabalhow2.backend.controller.request.RedirecionarManutencaoRequest;
import com.trabalhow2.backend.controller.request.RegistrarManutencaoRequest;
import com.trabalhow2.backend.controller.request.RejeitarOrcamentoRequest;
import com.trabalhow2.backend.controller.response.HistoricoSolicitacaoResponse;
import com.trabalhow2.backend.controller.response.ItemOrcamentoResponse;
import com.trabalhow2.backend.controller.response.OrcamentoResponse;
import com.trabalhow2.backend.controller.response.SolicitacaoResponse;
import com.trabalhow2.backend.controller.response.SolicitacaoResponse.ClienteResumoResponse;
import com.trabalhow2.backend.exception.AcessoNegadoException;
import com.trabalhow2.backend.exception.SolicitacaoNaoEncontradaException;
import com.trabalhow2.backend.exception.TransicaoStatusInvalidaException;
import com.trabalhow2.backend.model.Categoria;
import com.trabalhow2.backend.model.Cliente;
import com.trabalhow2.backend.model.Funcionario;
import com.trabalhow2.backend.model.HistoricoSolicitacao;
import com.trabalhow2.backend.model.ItemOrcamento;
import com.trabalhow2.backend.model.Manutencao;
import com.trabalhow2.backend.model.Orcamento;
import com.trabalhow2.backend.model.Solicitacao;
import com.trabalhow2.backend.model.Usuario;
import com.trabalhow2.backend.model.enums.StatusSolicitacao;
import com.trabalhow2.backend.repository.CategoriaRepository;
import com.trabalhow2.backend.repository.ClienteRepository;
import com.trabalhow2.backend.repository.FuncionarioRepository;
import com.trabalhow2.backend.repository.HistoricoSolicitacaoRepository;
import com.trabalhow2.backend.repository.ManutencaoRepository;
import com.trabalhow2.backend.repository.OrcamentoRepository;
import com.trabalhow2.backend.repository.SolicitacaoRepository;
import com.trabalhow2.backend.repository.UsuarioRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SolicitacaoService {

    private final SolicitacaoRepository solicitacaoRepository;
    private final ClienteRepository clienteRepository;
    private final CategoriaRepository categoriaRepository;
    private final FuncionarioRepository funcionarioRepository;
    private final HistoricoSolicitacaoRepository historicoRepository;
    private final OrcamentoRepository orcamentoRepository;
    private final ManutencaoRepository manutencaoRepository;
    private final UsuarioRepository usuarioRepository;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Transactional
    public SolicitacaoResponse abrirSolicitacao(AbrirSolicitacaoRequest request, Long usuarioIdLogado) {
        validarPerfilCliente(usuarioIdLogado);
        validarUsuarioLogadoComIdInformado(request.clienteId(), usuarioIdLogado, "cliente");

        Cliente cliente = clienteRepository.findById(request.clienteId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Cliente nao encontrado com ID: " + request.clienteId()));

        Categoria categoria = categoriaRepository.findByIdAndAtivoTrue(request.categoriaId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Categoria nao encontrada com ID: " + request.categoriaId()));

        Solicitacao solicitacao = new Solicitacao();
        solicitacao.setCliente(cliente);
        solicitacao.setDescricaoEquipamento(request.descricaoEquipamento());
        solicitacao.setCategoria(categoria);
        solicitacao.setDescricaoDefeito(request.descricaoDefeito());
        solicitacao.setStatus(StatusSolicitacao.ABERTA);
        solicitacao.setDataCriacao(LocalDateTime.now());
        solicitacao.setFuncionario(null);

        Solicitacao solicitacaoSalva = solicitacaoRepository.save(solicitacao);

        //Regista no histórico logo depois de criar a solicitação
        registrarMudancaHistorico(
                solicitacaoSalva, 
                null, // Sem estado anterior, ta abrindo agora
                StatusSolicitacao.ABERTA.name(), 
                cliente.getUsuario(), 
                "Solicitação aberta pelo cliente."
        );

        return paraResponse(solicitacaoSalva);
    }

    //Centraliza a criação do log para usar nos outros métodos (Aprovar, Rejeitar, etc)
    public void registrarMudancaHistorico(Solicitacao solicitacao, String estadoAnterior, String estadoNovo, Usuario responsavel, String observacoes) {
        registrarMudancaHistorico(solicitacao, estadoAnterior, estadoNovo, responsavel, observacoes, null, null);
    }

    public void registrarMudancaHistorico(
            Solicitacao solicitacao,
            String estadoAnterior,
            String estadoNovo,
            Usuario responsavel,
            String observacoes,
            Funcionario funcionarioOrigem,
            Funcionario funcionarioDestino) {
        HistoricoSolicitacao historico = (funcionarioOrigem != null || funcionarioDestino != null)
                ? HistoricoSolicitacao.criarRegistroRedirecionamento(
                        solicitacao,
                        estadoAnterior,
                        estadoNovo,
                        responsavel,
                        observacoes,
                        funcionarioOrigem,
                        funcionarioDestino
                )
                : HistoricoSolicitacao.criarRegistro(
                        solicitacao,
                        estadoAnterior,
                        estadoNovo,
                        responsavel,
                        observacoes
                );
        historicoRepository.save(historico);

        if (estadoAnterior != null && !estadoAnterior.equals(estadoNovo)){
                eventPublisher.publishEvent(new com.trabalhow2.backend.model.MudancaEstadoEvent(solicitacao, estadoAnterior, estadoNovo));
        }
    }

    //Busca a linha do tempo completa para a tela do Front
    @Transactional(readOnly = true)
    public List<HistoricoSolicitacaoResponse> buscarHistorico(Long solicitacaoId, Long usuarioIdLogado) {
        Solicitacao solicitacao = solicitacaoRepository.findByIdAndAtivoTrue(solicitacaoId)
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(solicitacaoId));
        validarAcessoSolicitacao(solicitacao, usuarioIdLogado);

        return historicoRepository.findBySolicitacaoIdOrderByDataHoraAsc(solicitacaoId)
                .stream()
                .map(HistoricoSolicitacaoResponse::new)
                .toList();
    }

    @Transactional(readOnly = true)
    public SolicitacaoResponse buscarPorId(Long id, Long usuarioIdLogado) {
        Solicitacao solicitacao = solicitacaoRepository.findByIdAndAtivoTrue(id)
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(id));
        validarAcessoSolicitacao(solicitacao, usuarioIdLogado);
        return paraResponse(solicitacao);
    }

    @Transactional(readOnly = true)
    public List<SolicitacaoResponse> listarPorCliente(Long clienteId, Long usuarioIdLogado) {
        validarUsuarioLogadoComIdInformado(clienteId, usuarioIdLogado, "cliente");

        return solicitacaoRepository.findByClienteIdAndAtivoTrueOrderByDataCriacaoAsc(clienteId)
                .stream()
                .map(this::paraResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SolicitacaoResponse> listarPorFuncionario(Long funcionarioId, Long usuarioIdLogado) {
        validarUsuarioLogadoComIdInformado(funcionarioId, usuarioIdLogado, "funcionário");

        return solicitacaoRepository
                .listarParaVisualizacaoFuncionario(funcionarioId, StatusSolicitacao.REDIRECIONADA)
                .stream()
                .map(this::paraResponse)
                .toList();
    }
    @Transactional(readOnly = true)
        public List<SolicitacaoResponse> listarAbertas(Long usuarioIdLogado) {
        validarPerfilFuncionario(usuarioIdLogado);

        return solicitacaoRepository.findByStatusAndAtivoTrueOrderByDataCriacaoAsc(StatusSolicitacao.ABERTA)
                .stream()
                .map(this::paraResponse)
                .toList();
        }

    @Transactional(readOnly = true)
    public Page<SolicitacaoResponse> buscarComFiltrosPaginado(
            String status,
            Long categoriaId,
            Long funcionarioId,
            LocalDateTime dataInicio,
            LocalDateTime dataFim,
            Pageable pageable) {
        
        StatusSolicitacao statusEnum = null;
        if (status != null && !status.trim().isEmpty()) {
            try {
                statusEnum = StatusSolicitacao.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
            }
        }

        Page<Solicitacao> paginaSolicitacoes = solicitacaoRepository.buscarComFiltrosPaginado(
                statusEnum, categoriaId, funcionarioId, dataInicio, dataFim, pageable);

        return paginaSolicitacoes.map(this::paraResponse);
    }

    // RF005 — Busca o orçamento mais recente de uma solicitação com os itens detalhados
    @Transactional(readOnly = true)
    public OrcamentoResponse buscarUltimoOrcamento(Long solicitacaoId, Long usuarioIdLogado) {
        Solicitacao solicitacao = solicitacaoRepository.findByIdAndAtivoTrue(solicitacaoId)
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(solicitacaoId));
        validarAcessoSolicitacao(solicitacao, usuarioIdLogado);

        return orcamentoRepository.findBySolicitacaoIdOrderByVersaoDesc(solicitacaoId)
                .stream()
                .findFirst()
                .map(this::paraOrcamentoResponse)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nenhum orçamento encontrado para a solicitação #" + solicitacaoId));
    }

    // RF010 — Efetua o orçamento de uma solicitação
    @Transactional
    public OrcamentoResponse efetuarOrcamento(Long solicitacaoId, EfetuarOrcamentoRequest request, Long funcionarioId) {
        Solicitacao solicitacao = solicitacaoRepository.findByIdAndAtivoTrue(solicitacaoId)
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(solicitacaoId));

        if (solicitacao.getStatus() != StatusSolicitacao.ABERTA) {
            throw new IllegalArgumentException(
                    "A solicitação #" + solicitacaoId + " não está com status ABERTA (status atual: " + solicitacao.getStatus() + ").");
        }

        Funcionario funcionario = funcionarioRepository.findByIdAndUsuarioAtivoTrue(funcionarioId)
                .orElseThrow(() -> new EntityNotFoundException("Funcionário não encontrado com ID: " + funcionarioId));

        int versao = (int) orcamentoRepository.countBySolicitacaoId(solicitacaoId) + 1;

        Orcamento orcamento = new Orcamento();
        orcamento.setSolicitacao(solicitacao);
        orcamento.setFuncionario(funcionario);
        orcamento.setDataHora(LocalDateTime.now());
        orcamento.setVersao(versao);

        BigDecimal valorTotal = BigDecimal.ZERO;
        for (ItemOrcamentoRequest itemReq : request.itens()) {
            ItemOrcamento item = new ItemOrcamento();
            item.setOrcamento(orcamento);
            item.setTipo(itemReq.tipo());
            item.setDescricao(itemReq.descricao());
            item.setQuantidade(itemReq.quantidade());
            item.setValorUnitario(itemReq.valorUnitario());
            BigDecimal itemTotal = itemReq.valorUnitario().multiply(BigDecimal.valueOf(itemReq.quantidade()));
            item.setValorTotal(itemTotal);
            orcamento.getItens().add(item);
            valorTotal = valorTotal.add(itemTotal);
        }

        orcamento.setValorTotal(valorTotal);

        solicitacao.setValorOrcado(valorTotal);
        solicitacao.setStatus(StatusSolicitacao.ORCADA);

        Orcamento orcamentoSalvo = orcamentoRepository.save(orcamento);
        solicitacaoRepository.save(solicitacao);

        registrarMudancaHistorico(
                solicitacao,
                StatusSolicitacao.ABERTA.name(),
                StatusSolicitacao.ORCADA.name(),
                funcionario.getUsuario(),
                "Orçamento v" + versao + " registrado. Total: R$ " + valorTotal
        );

        return paraOrcamentoResponse(orcamentoSalvo);
    }

    // RF006 - Aprova o orcamento de uma solicitacao pelo cliente
    @Transactional
    public SolicitacaoResponse aprovarOrcamento(Long solicitacaoId, Long clienteId) {
        Solicitacao solicitacao = buscarSolicitacaoOrcadaDoCliente(solicitacaoId, clienteId);
        Cliente cliente = solicitacao.getCliente();

        solicitacao.setStatus(StatusSolicitacao.APROVADA);
        solicitacao.setMotivoRejeicao(null);

        Solicitacao solicitacaoSalva = solicitacaoRepository.save(solicitacao);

        registrarMudancaHistorico(
                solicitacaoSalva,
                StatusSolicitacao.ORCADA.name(),
                StatusSolicitacao.APROVADA.name(),
                cliente.getUsuario(),
                "Orçamento aprovado pelo cliente."
        );

        return paraResponse(solicitacaoSalva);
    }

    // RF007 - Rejeita o orcamento de uma solicitacao pelo cliente
    @Transactional
    public SolicitacaoResponse rejeitarOrcamento(Long solicitacaoId, RejeitarOrcamentoRequest request, Long clienteId) {
        Solicitacao solicitacao = buscarSolicitacaoOrcadaDoCliente(solicitacaoId, clienteId);
        Cliente cliente = solicitacao.getCliente();
        String motivo = request.motivo().trim();

        solicitacao.setStatus(StatusSolicitacao.REJEITADA);
        solicitacao.setMotivoRejeicao(motivo);

        Solicitacao solicitacaoSalva = solicitacaoRepository.save(solicitacao);

        registrarMudancaHistorico(
                solicitacaoSalva,
                StatusSolicitacao.ORCADA.name(),
                StatusSolicitacao.REJEITADA.name(),
                cliente.getUsuario(),
                "Orçamento rejeitado pelo cliente. Motivo: " + motivo
        );

        return paraResponse(solicitacaoSalva);
    }

    // RF009 - Resgata o serviço rejeitado e retorna a solicitação ao estado APROVADA
    @Transactional
    public SolicitacaoResponse resgatarServico(Long solicitacaoId, Long clienteId) {
        Solicitacao solicitacao = solicitacaoRepository.findByIdAndAtivoTrue(solicitacaoId)
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(solicitacaoId));

        if (solicitacao.getStatus() != StatusSolicitacao.REJEITADA) {
            throw new TransicaoStatusInvalidaException(
                    "A solicitacao #" + solicitacaoId + " deve estar REJEITADA para ser resgatada (status atual: "
                            + solicitacao.getStatus() + ").");
        }

        if (solicitacao.getCliente() == null || !solicitacao.getCliente().getId().equals(clienteId)) {
            throw new IllegalArgumentException("Solicitacao nao pertence ao cliente informado.");
        }

        Cliente cliente = solicitacao.getCliente();
        solicitacao.setStatus(StatusSolicitacao.APROVADA);
        Solicitacao solicitacaoSalva = solicitacaoRepository.save(solicitacao);

        registrarMudancaHistorico(
                solicitacaoSalva,
                StatusSolicitacao.REJEITADA.name(),
                StatusSolicitacao.APROVADA.name(),
                cliente.getUsuario(),
                "Serviço resgatado pelo cliente."
        );

        return paraResponse(solicitacaoSalva);
    }

    // RF014 - Confirma o pagamento pelo cliente. Pre-condicao: OS ARRUMADA.
    @Transactional
    public SolicitacaoResponse confirmarPagamento(Long solicitacaoId, ConfirmarPagamentoRequest request, Long clienteId) {
        Solicitacao solicitacao = solicitacaoRepository.findByIdAndAtivoTrue(solicitacaoId)
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(solicitacaoId));

        if (solicitacao.getStatus() != StatusSolicitacao.ARRUMADA) {
            throw new TransicaoStatusInvalidaException(
                    "A solicitacao #" + solicitacaoId + " deve estar ARRUMADA para confirmar pagamento (status atual: "
                            + solicitacao.getStatus() + ").");
        }

        if (solicitacao.getCliente() == null || !solicitacao.getCliente().getId().equals(clienteId)) {
            throw new IllegalArgumentException("Solicitacao nao pertence ao cliente informado.");
        }

        if (manutencaoRepository.findBySolicitacaoId(solicitacaoId).isEmpty()) {
            throw new TransicaoStatusInvalidaException(
                    "A solicitacao #" + solicitacaoId + " precisa ter manutencao registrada antes do pagamento.");
        }

        BigDecimal valorOrcado = solicitacao.getValorOrcado();
        if (valorOrcado == null) {
            throw new TransicaoStatusInvalidaException(
                    "A solicitação #" + solicitacaoId + " não possui orçamento aprovado para conciliação do pagamento.");
        }

        BigDecimal valorPago = request.valorPago();
        boolean pagamentoDivergente = valorPago.compareTo(valorOrcado) != 0;
        if (pagamentoDivergente) {
            log.warn(
                    "RF014 - Divergência no pagamento da solicitação #{}: valor pago {} difere do orçamento aprovado {}.",
                    solicitacaoId,
                    valorPago,
                    valorOrcado
            );
        }

        Cliente cliente = solicitacao.getCliente();
        LocalDateTime dataHoraPagamento = LocalDateTime.now();
        solicitacao.setValorPago(valorPago);
        solicitacao.setDataHoraPagamento(dataHoraPagamento);
        solicitacao.setPagamentoDivergente(pagamentoDivergente);
        solicitacao.setStatus(StatusSolicitacao.PAGA);
        Solicitacao solicitacaoSalva = solicitacaoRepository.save(solicitacao);

        String observacao = pagamentoDivergente
                ? "Pagamento confirmado pelo cliente com divergencia. Valor pago: R$ " + valorPago
                        + ". Orçamento aprovado: R$ " + valorOrcado
                : "Pagamento confirmado pelo cliente. Valor pago: R$ " + valorPago;

        registrarMudancaHistorico(
                solicitacaoSalva,
                StatusSolicitacao.ARRUMADA.name(),
                StatusSolicitacao.PAGA.name(),
                cliente.getUsuario(),
                observacao
        );

        return paraResponse(solicitacaoSalva);
    }

    // RF013 — Registra a manutenção realizada pelo técnico. Pré-condição: OS deve estar APROVADA ou REDIRECIONADA.
    @Transactional
    public SolicitacaoResponse registrarManutencao(Long solicitacaoId, RegistrarManutencaoRequest request, Long funcionarioId) {
        Solicitacao solicitacao = solicitacaoRepository.findByIdAndAtivoTrue(solicitacaoId)
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(solicitacaoId));

        // Pré-condição: somente APROVADA ou REDIRECIONADA aceitam manutenção
        if (solicitacao.getStatus() != StatusSolicitacao.APROVADA
                && solicitacao.getStatus() != StatusSolicitacao.REDIRECIONADA) {
            throw new TransicaoStatusInvalidaException(
                    "A solicitação #" + solicitacaoId + " deve estar APROVADA ou REDIRECIONADA para registrar manutenção (status atual: "
                            + solicitacao.getStatus() + ").");
        }

        Funcionario funcionario = funcionarioRepository.findByIdAndUsuarioAtivoTrue(funcionarioId)
                .orElseThrow(() -> new EntityNotFoundException("Funcionário não encontrado com ID: " + funcionarioId));

        Manutencao manutencao = new Manutencao();
        manutencao.setSolicitacao(solicitacao);
        manutencao.setFuncionario(funcionario);
        manutencao.setDataHora(LocalDateTime.now());
        manutencao.setDescricaoManutencao(request.descricaoManutencao());
        manutencao.setOrientacoesCliente(request.orientacoesCliente());
        manutencao.setPecasUsadas(request.pecasUsadas());
        manutencao.setTempoGasto(request.tempoGasto());

        manutencaoRepository.save(manutencao);

        String estadoAnterior = solicitacao.getStatus().name();
        solicitacao.setStatus(StatusSolicitacao.ARRUMADA);
        Solicitacao solicitacaoSalva = solicitacaoRepository.save(solicitacao);

        String observacaoHistorico = "Manutenção registrada pelo técnico."
                + " Descrição da manutenção: " + request.descricaoManutencao()
                + " Orientações para o cliente: " + request.orientacoesCliente();

        registrarMudancaHistorico(
                solicitacaoSalva,
                estadoAnterior,
                StatusSolicitacao.ARRUMADA.name(),
                funcionario.getUsuario(),
                observacaoHistorico
        );

        return paraResponse(solicitacaoSalva);
    }

    // RF016 — Redireciona uma OS aprovada para outro técnico responsável.
    @Transactional
    public SolicitacaoResponse redirecionarManutencao(Long solicitacaoId, RedirecionarManutencaoRequest request, Long funcionarioOrigemId) {
        Solicitacao solicitacao = solicitacaoRepository.findByIdAndAtivoTrue(solicitacaoId)
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(solicitacaoId));

        if (solicitacao.getStatus() != StatusSolicitacao.APROVADA) {
            throw new TransicaoStatusInvalidaException(
                    "A solicitação #" + solicitacaoId + " deve estar APROVADA para redirecionar (status atual: "
                            + solicitacao.getStatus() + ").");
        }

        Funcionario funcionarioOrigem = funcionarioRepository.findByIdAndUsuarioAtivoTrue(funcionarioOrigemId)
                .orElseThrow(() -> new EntityNotFoundException("Funcionário origem não encontrado com ID: " + funcionarioOrigemId));

        Funcionario funcionarioDestino = funcionarioRepository.findByIdAndUsuarioAtivoTrue(request.funcionarioDestinoId())
                .orElseThrow(() -> new EntityNotFoundException("Funcionário destino não encontrado com ID: " + request.funcionarioDestinoId()));

        if (funcionarioOrigem.getId().equals(funcionarioDestino.getId())) {
            throw new TransicaoStatusInvalidaException("Não é possível redirecionar a OS para o mesmo funcionário.");
        }

        solicitacao.setFuncionario(funcionarioDestino);
        solicitacao.setStatus(StatusSolicitacao.REDIRECIONADA);

        Solicitacao solicitacaoSalva = solicitacaoRepository.save(solicitacao);

        String origemNome = funcionarioOrigem.getUsuario() != null ? funcionarioOrigem.getUsuario().getNome() : "Funcionário " + funcionarioOrigem.getId();
        String destinoNome = funcionarioDestino.getUsuario() != null ? funcionarioDestino.getUsuario().getNome() : "Funcionário " + funcionarioDestino.getId();

        registrarMudancaHistorico(
                solicitacaoSalva,
                StatusSolicitacao.APROVADA.name(),
                StatusSolicitacao.REDIRECIONADA.name(),
                funcionarioOrigem.getUsuario(),
                "Manutenção redirecionada de " + origemNome + " para " + destinoNome + ". Motivo: " + request.motivo().trim(),
                funcionarioOrigem,
                funcionarioDestino
        );

        return paraResponse(solicitacaoSalva);
    }

    // RF015 - Finaliza a OS. Pre-condicoes: pagamento confirmado e manutencao registrada.
    @Transactional
    public SolicitacaoResponse finalizarSolicitacao(Long solicitacaoId, Long funcionarioId) {
        Solicitacao solicitacao = solicitacaoRepository.findByIdAndAtivoTrue(solicitacaoId)
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(solicitacaoId));

        if (solicitacao.getStatus() == StatusSolicitacao.FINALIZADA) {
            throw new TransicaoStatusInvalidaException(
                    "A solicitacao #" + solicitacaoId + " ja esta FINALIZADA e nao permite novas transicoes.");
        }

        if (solicitacao.getStatus() != StatusSolicitacao.PAGA) {
            throw new TransicaoStatusInvalidaException(
                    "A solicitacao #" + solicitacaoId + " deve estar PAGA para finalizar (status atual: "
                            + solicitacao.getStatus() + ").");
        }

        if (manutencaoRepository.findBySolicitacaoId(solicitacaoId).isEmpty()) {
            throw new TransicaoStatusInvalidaException(
                    "A solicitacao #" + solicitacaoId + " precisa ter manutencao registrada antes da finalizacao.");
        }

        if (solicitacao.getDataHoraPagamento() == null || solicitacao.getValorPago() == null) {
            throw new TransicaoStatusInvalidaException(
                    "A solicitacao #" + solicitacaoId + " precisa ter pagamento confirmado antes da finalizacao.");
        }

        Funcionario funcionario = funcionarioRepository.findByIdAndUsuarioAtivoTrue(funcionarioId)
                .orElseThrow(() -> new EntityNotFoundException("Funcionario nao encontrado com ID: " + funcionarioId));

        solicitacao.setStatus(StatusSolicitacao.FINALIZADA);
        Solicitacao solicitacaoSalva = solicitacaoRepository.save(solicitacao);

        registrarMudancaHistorico(
                solicitacaoSalva,
                StatusSolicitacao.PAGA.name(),
                StatusSolicitacao.FINALIZADA.name(),
                funcionario.getUsuario(),
                "Solicitacao finalizada pelo funcionario."
        );

        return paraResponse(solicitacaoSalva);
    }

    private void validarUsuarioLogadoComIdInformado(Long idInformado, Long usuarioIdLogado, String tipoRecurso) {
        if (usuarioIdLogado == null || !usuarioIdLogado.equals(idInformado)) {
            throw new AcessoNegadoException(
                    "Usuário logado não autorizado a consultar solicitações deste " + tipoRecurso + "."
            );
        }
    }

    private void validarPerfilFuncionario(Long usuarioIdLogado) {
        Usuario usuario = usuarioRepository.findByIdAndAtivoTrue(usuarioIdLogado)
                .orElseThrow(() -> new AcessoNegadoException("Usuário não autorizado."));

        if (usuario.getPerfil() != com.trabalhow2.backend.model.enums.Perfil.FUNCIONARIO) {
            throw new AcessoNegadoException("Apenas funcionários podem consultar este recurso.");
        }
    }

    private void validarPerfilCliente(Long usuarioIdLogado) {
        Usuario usuario = usuarioRepository.findByIdAndAtivoTrue(usuarioIdLogado)
                .orElseThrow(() -> new AcessoNegadoException("Usuário não autorizado."));

        if (usuario.getPerfil() != com.trabalhow2.backend.model.enums.Perfil.CLIENTE) {
            throw new AcessoNegadoException("Apenas clientes podem executar este recurso.");
        }
    }
    private void validarAcessoSolicitacao(Solicitacao solicitacao, Long usuarioIdLogado) {
        Usuario usuario = usuarioRepository.findByIdAndAtivoTrue(usuarioIdLogado)
                .orElseThrow(() -> new AcessoNegadoException("Usuário não autorizado."));

        if (usuario.getPerfil() == com.trabalhow2.backend.model.enums.Perfil.CLIENTE) {
            boolean acessoCliente = solicitacao.getCliente() != null
                    && solicitacao.getCliente().getId() != null
                    && solicitacao.getCliente().getId().equals(usuarioIdLogado);

            if (!acessoCliente) {
                throw new AcessoNegadoException("Usuário logado não possui acesso à solicitação informada.");
            }
            return;
        }

        if (usuario.getPerfil() == com.trabalhow2.backend.model.enums.Perfil.FUNCIONARIO) {
            if (solicitacao.getStatus() == StatusSolicitacao.REDIRECIONADA) {
                boolean acessoRedirecionada = solicitacao.getFuncionario() != null
                        && solicitacao.getFuncionario().getId() != null
                        && solicitacao.getFuncionario().getId().equals(usuarioIdLogado);

                if (!acessoRedirecionada) {
                    throw new AcessoNegadoException(
                            "Solicitações redirecionadas só podem ser acessadas pelo funcionário de destino.");
                }
            }
            return;
        }

        throw new AcessoNegadoException("Perfil de usuário não autorizado para acessar solicitações.");
    }

    private Solicitacao buscarSolicitacaoOrcadaDoCliente(Long solicitacaoId, Long clienteId) {
        Solicitacao solicitacao = solicitacaoRepository.findByIdAndAtivoTrue(solicitacaoId)
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(solicitacaoId));

        if (solicitacao.getStatus() != StatusSolicitacao.ORCADA) {
            throw new TransicaoStatusInvalidaException(
                    "A solicitacao #" + solicitacaoId + " deve estar ORCADA para aprovar ou rejeitar (status atual: "
                            + solicitacao.getStatus() + ").");
        }

        if (solicitacao.getCliente() == null || !solicitacao.getCliente().getId().equals(clienteId)) {
            throw new IllegalArgumentException("Solicitacao nao pertence ao cliente informado.");
        }

        return solicitacao;
    }

    private OrcamentoResponse paraOrcamentoResponse(Orcamento orcamento) {
        List<ItemOrcamentoResponse> itensResponse = orcamento.getItens().stream()
                .map(item -> new ItemOrcamentoResponse(
                        item.getId(),
                        item.getTipo().name(),
                        item.getDescricao(),
                        item.getQuantidade(),
                        item.getValorUnitario(),
                        item.getValorTotal()
                ))
                .toList();
        return new OrcamentoResponse(
                orcamento.getId(),
                orcamento.getVersao(),
                orcamento.getDataHora(),
                itensResponse,
                orcamento.getValorTotal()
        );
    }

    private SolicitacaoResponse paraResponse(Solicitacao solicitacao) {
        ManutencaoResumo manutencaoResumo = obterResumoManutencao(solicitacao);

        return new SolicitacaoResponse(
                solicitacao.getId(),
                solicitacao.getDescricaoEquipamento(),
                solicitacao.getCategoria() != null ? solicitacao.getCategoria().getNome() : null,
                solicitacao.getDescricaoDefeito(),
                solicitacao.getStatus().name(),
                solicitacao.getDataCriacao(),
                solicitacao.getValorOrcado(),
                solicitacao.getValorPago(),
                solicitacao.getDataHoraPagamento(),
                solicitacao.isPagamentoDivergente(),
                solicitacao.getMotivoRejeicao(),
                paraClienteResumo(solicitacao.getCliente()),
                manutencaoResumo.descricaoManutencao(),
                manutencaoResumo.orientacoesCliente()
        );
    }

    private ManutencaoResumo obterResumoManutencao(Solicitacao solicitacao) {
        StatusSolicitacao status = solicitacao.getStatus();
        if (status != StatusSolicitacao.ARRUMADA
                && status != StatusSolicitacao.PAGA
                && status != StatusSolicitacao.FINALIZADA) {
            return ManutencaoResumo.vazio();
        }

        return manutencaoRepository.findTopBySolicitacaoIdOrderByDataHoraDesc(solicitacao.getId())
                .map(manutencao -> new ManutencaoResumo(
                        manutencao.getDescricaoManutencao(),
                        manutencao.getOrientacoesCliente()
                ))
                .orElseGet(ManutencaoResumo::vazio);
    }

    private record ManutencaoResumo(String descricaoManutencao, String orientacoesCliente) {
        private static ManutencaoResumo vazio() {
            return new ManutencaoResumo(null, null);
        }
    }

    private ClienteResumoResponse paraClienteResumo(Cliente cliente) {
        if (cliente == null) {
            return null;
        }

        return new ClienteResumoResponse(
                cliente.getId(),
                cliente.getUsuario() != null ? cliente.getUsuario().getNome() : "",
                cliente.getUsuario() != null ? cliente.getUsuario().getEmail() : "",
                cliente.getCpf(),
                cliente.getTelefone(),
                montarEndereco(cliente)
        );
    }

    private String montarEndereco(Cliente cliente) {
        return java.util.stream.Stream.of(
                        cliente.getLogradouro(),
                        cliente.getNumero(),
                        cliente.getComplemento(),
                        cliente.getBairro(),
                        cliente.getCidade(),
                        cliente.getEstado()
                )
                .filter(valor -> valor != null && !valor.isBlank())
                .reduce((parte1, parte2) -> parte1 + ", " + parte2)
                .orElse("");
    }
}

