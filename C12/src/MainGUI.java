import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.function.DoubleUnaryOperator;

public class MainGUI extends JFrame {

    static final Color FUNDO       = new Color(0xF3F5F9);
    static final Color CARTAO      = Color.WHITE;
    static final Color BORDA       = new Color(0xDCE1E9);
    static final Color TEXTO       = new Color(0x1F2937);
    static final Color TEXTO_SUAVE = new Color(0x6B7280);
    static final Color TRILHO      = new Color(0xE6EAF0);
    static final Color VERDE       = new Color(0x15803D);
    static final Color AMARELO     = new Color(0xB45309);
    static final Color VERMELHO    = new Color(0xDC2626);
    static final Color AZUL        = new Color(0x2563EB);
    static final Color CIANO       = new Color(0x0E7490);
    static final Color ROXO        = new Color(0x7C3AED);
    static final Color DESABILITADO = new Color(0x93A3BF);

    static final String FONTE = "Segoe UI";

    // As duas simulações usam sleep e disputam o lock do GerenciadorVisual; rodando juntas,
    // uma distorceria a medição da outra. Por isso só uma roda por vez.
    static final String DICA_OCUPADO = "Aguarde a simulação em andamento terminar (só uma roda por vez para não distorcer os tempos).";

    // 0,1 GB = 102 MB: garante pelo menos 1 MB por thread mesmo com 64 threads
    static final double MIN_GB = 0.1;

    private final JSpinner spinnerTamanho = new JSpinner(new SpinnerNumberModel(1.0, MIN_GB, 100.0, 0.5));
    private final JSpinner spinnerThreads = new JSpinner(new SpinnerNumberModel(8, Main.MIN_THREADS, Main.MAX_THREADS, 1));
    private final JButton botaoIniciar = new JButton("Iniciar comparação");
    private final JLabel lblArquivo = new JLabel(" ");

    private final JLabel lblEtapa = new JLabel("Configure o arquivo e o número de threads e clique em Iniciar.");
    private final JLabel lblTempoEtapa = new JLabel(" ");
    private final JPanel painelLinhas = new JPanel(new GridBagLayout());

    private final BarraProgresso barraTotal = new BarraProgresso(CIANO, 30);
    private final JLabel lblTotalMB = new JLabel("0 / 0 MB");
    private final JLabel lblTotalVel = new JLabel("0,0 MB/s");

    private final GraficoComparacao grafico = new GraficoComparacao();
    private final JLabel lblSpeedup = new JLabel(" ");
    private final JTextArea txtResultado = new JTextArea();

    private final Timer timer = new Timer(100, e -> atualizarTela());

    private GerenciadorVisual gerenciadorAtual;
    private LinhaThread[] linhas = new LinhaThread[0];
    private int totalMBAtual;
    private int etapaAtual;
    private long t0Atual;
    private long nanosAnterior;
    private int[] progressoAnterior;
    private double[] velocidades;
    private double[] tempoConclusao;
    private String resumoGargalo = "";

    private final PainelAnalise painelAnalise;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainGUI().setVisible(true));
    }

    MainGUI() {
        super("Monitor Visual de Paralelismo (Threads)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JPanel raiz = new JPanel(new BorderLayout(0, 12));
        raiz.setBackground(FUNDO);
        raiz.setBorder(new EmptyBorder(14, 14, 14, 14));
        raiz.add(criarCabecalho(), BorderLayout.NORTH);
        raiz.add(criarPainelDownload(), BorderLayout.CENTER);
        raiz.add(criarPainelResultado(), BorderLayout.SOUTH);

        painelAnalise = new PainelAnalise();

        JTabbedPane abas = new JTabbedPane();
        abas.setFont(new Font(FONTE, Font.BOLD, 14));
        abas.setBackground(FUNDO);
        abas.setOpaque(true);
        abas.addTab("Comparação: 1 vs N threads", raiz);
        abas.addTab("Análise: Tempo x Threads", painelAnalise);
        setContentPane(abas);

        botaoIniciar.addActionListener(e -> iniciar());
        spinnerTamanho.addChangeListener(e -> atualizarInfoArquivo());
        atualizarInfoArquivo();

        setSize(1100, 820);
        setMinimumSize(new Dimension(900, 640));
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------ layout

    private JComponent criarCabecalho() {
        JPanel cartao = cartao(new BorderLayout(0, 10));

        JLabel titulo = new JLabel("Monitor Visual de Paralelismo (Threads)");
        titulo.setFont(new Font(FONTE, Font.BOLD, 22));
        titulo.setForeground(TEXTO);
        JLabel subtitulo = new JLabel("Download simulado de um arquivo dividido em pedaços — 1 thread vs. N threads");
        subtitulo.setFont(new Font(FONTE, Font.PLAIN, 14));
        subtitulo.setForeground(TEXTO_SUAVE);

        JPanel textos = new JPanel(new GridLayout(2, 1));
        textos.setOpaque(false);
        textos.add(titulo);
        textos.add(subtitulo);

        spinnerTamanho.setEditor(new JSpinner.NumberEditor(spinnerTamanho, "0.00"));
        estilizarSpinner(spinnerTamanho);
        estilizarSpinner(spinnerThreads);

        estilizarBotao(botaoIniciar, AZUL);

        lblArquivo.setFont(new Font(FONTE, Font.PLAIN, 13));
        lblArquivo.setBorder(new EmptyBorder(0, 0, 0, 12));

        JPanel controles = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        controles.setOpaque(false);
        controles.add(rotulo("Tamanho do arquivo (GB):"));
        controles.add(spinnerTamanho);
        controles.add(Box.createHorizontalStrut(8));
        controles.add(rotulo("Threads (" + Main.MIN_THREADS + " a " + Main.MAX_THREADS + "):"));
        controles.add(spinnerThreads);
        controles.add(Box.createHorizontalStrut(8));
        controles.add(botaoIniciar);
        controles.add(Box.createHorizontalStrut(8));
        controles.add(lblArquivo);

        cartao.add(textos, BorderLayout.NORTH);
        cartao.add(controles, BorderLayout.CENTER);
        return cartao;
    }

    private JComponent criarPainelDownload() {
        JPanel cartao = cartao(new BorderLayout(0, 10));

        lblEtapa.setFont(new Font(FONTE, Font.BOLD, 16));
        lblEtapa.setForeground(TEXTO);
        lblEtapa.setBorder(new EmptyBorder(0, 0, 0, 12));
        lblTempoEtapa.setFont(new Font(FONTE, Font.BOLD, 16));
        lblTempoEtapa.setForeground(TEXTO_SUAVE);

        JPanel topo = new JPanel(new BorderLayout());
        topo.setOpaque(false);
        topo.add(lblEtapa, BorderLayout.WEST);
        topo.add(lblTempoEtapa, BorderLayout.EAST);

        painelLinhas.setBackground(CARTAO);
        JScrollPane scroll = new JScrollPane(painelLinhas);
        scroll.setBorder(new LineBorder(BORDA));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JLabel lblTotal = new JLabel("TOTAL");
        lblTotal.setFont(new Font(FONTE, Font.BOLD, 16));
        lblTotal.setForeground(TEXTO);
        lblTotalMB.setFont(new Font(FONTE, Font.PLAIN, 14));
        lblTotalMB.setForeground(TEXTO);
        lblTotalVel.setFont(new Font(FONTE, Font.BOLD, 14));
        lblTotalVel.setForeground(CIANO);
        fixarLargura(lblTotalMB, 150);
        fixarLargura(lblTotalVel, 110);
        lblTotalMB.setHorizontalAlignment(SwingConstants.RIGHT);
        lblTotalVel.setHorizontalAlignment(SwingConstants.RIGHT);

        JPanel direita = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        direita.setOpaque(false);
        direita.add(lblTotalMB);
        direita.add(lblTotalVel);

        JPanel total = new JPanel(new BorderLayout(12, 0));
        total.setOpaque(false);
        total.add(lblTotal, BorderLayout.WEST);
        total.add(barraTotal, BorderLayout.CENTER);
        total.add(direita, BorderLayout.EAST);

        cartao.add(topo, BorderLayout.NORTH);
        cartao.add(scroll, BorderLayout.CENTER);
        cartao.add(total, BorderLayout.SOUTH);
        return cartao;
    }

    private JComponent criarPainelResultado() {
        JPanel cartao = cartao(new BorderLayout(16, 6));

        JLabel titulo = new JLabel("Comparação de tempo");
        titulo.setFont(new Font(FONTE, Font.BOLD, 16));
        titulo.setForeground(TEXTO);

        lblSpeedup.setFont(new Font(FONTE, Font.BOLD, 30));
        lblSpeedup.setForeground(TEXTO_SUAVE);

        txtResultado.setEditable(false);
        txtResultado.setFocusable(false);
        txtResultado.setOpaque(false);
        txtResultado.setLineWrap(true);
        txtResultado.setWrapStyleWord(true);
        txtResultado.setFont(new Font(FONTE, Font.PLAIN, 14));
        txtResultado.setForeground(TEXTO);
        txtResultado.setText("O resultado aparece aqui quando as duas etapas terminarem.");

        JPanel texto = new JPanel(new BorderLayout(0, 4));
        texto.setOpaque(false);
        texto.setPreferredSize(new Dimension(540, 180));
        texto.add(lblSpeedup, BorderLayout.NORTH);
        texto.add(txtResultado, BorderLayout.CENTER);

        cartao.add(titulo, BorderLayout.NORTH);
        cartao.add(grafico, BorderLayout.CENTER);
        cartao.add(texto, BorderLayout.EAST);
        return cartao;
    }

    private void montarLinhas(GerenciadorVisual gerenciador, int numThreads, int totalMB) {
        painelLinhas.removeAll();

        int alturaBarra = numThreads <= 8 ? 24 : numThreads <= 16 ? 18 : 14;
        int tamFonte = numThreads <= 16 ? 14 : 12;
        int respiro = numThreads <= 16 ? 4 : 2;

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.insets = new Insets(6, 10, 6, 10);
        c.anchor = GridBagConstraints.WEST;
        String[] cabecalhos = { "Thread", "Faixa (MB)", "Status", "Progresso", "%", "Velocidade" };
        for (int col = 0; col < cabecalhos.length; col++) {
            JLabel h = new JLabel(cabecalhos[col]);
            h.setFont(new Font(FONTE, Font.BOLD, 12));
            h.setForeground(TEXTO_SUAVE);
            c.gridx = col;
            c.weightx = col == 3 ? 1 : 0;
            c.fill = col == 3 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
            c.anchor = col >= 4 ? GridBagConstraints.EAST : GridBagConstraints.WEST;
            painelLinhas.add(h, c);
        }

        c.insets = new Insets(respiro, 10, respiro, 10);
        linhas = new LinhaThread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            EstadoPedaco p = gerenciador.getPedaco(i);
            LinhaThread l = new LinhaThread(i, p, alturaBarra, tamFonte);
            linhas[i] = l;

            c.gridy = i + 1;
            JComponent[] celulas = { l.nome, l.faixa, l.status, l.barra, l.porcentagem, l.velocidade };
            for (int col = 0; col < celulas.length; col++) {
                c.gridx = col;
                c.weightx = col == 3 ? 1 : 0;
                c.fill = col == 3 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
                c.anchor = col >= 4 ? GridBagConstraints.EAST : GridBagConstraints.WEST;
                painelLinhas.add(celulas[col], c);
            }
        }

        c.gridy = numThreads + 1;
        c.gridx = 0;
        c.weighty = 1;
        c.gridwidth = cabecalhos.length;
        painelLinhas.add(Box.createGlue(), c);

        painelLinhas.revalidate();
        painelLinhas.repaint();

        gerenciadorAtual = gerenciador;
        totalMBAtual = totalMB;
        progressoAnterior = new int[numThreads];
        velocidades = new double[numThreads];
        tempoConclusao = new double[numThreads];
        java.util.Arrays.fill(tempoConclusao, Double.NaN);
        barraTotal.setValor(0, "0%");
        lblTotalMB.setText("0 / " + totalMB + " MB");
        lblTotalVel.setText("0,0 MB/s");
    }

    // ------------------------------------------------------------------ simulação

    private void iniciar() {
        try {
            spinnerTamanho.commitEdit();
            spinnerThreads.commitEdit();
        } catch (ParseException ignored) {}

        double tamanhoGB = ((Number) spinnerTamanho.getValue()).doubleValue();
        int numThreads = ((Number) spinnerThreads.getValue()).intValue();
        int totalMB = (int) Math.round(tamanhoGB * 1024);

        setOcupado(true);
        grafico.reiniciar(numThreads);
        lblSpeedup.setText("…");
        lblSpeedup.setForeground(TEXTO_SUAVE);
        txtResultado.setText("Aguardando as duas etapas terminarem.");

        Thread simulacao = new Thread(() -> {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    etapaAtual = 1;
                    lblEtapa.setText("Etapa 1/2: baixando com 1 thread (referência)");
                });
                double tempoUnitario = rodarDownload(1, totalMB);
                SwingUtilities.invokeAndWait(() -> grafico.setTempo(1, tempoUnitario));

                SwingUtilities.invokeAndWait(() -> {
                    etapaAtual = 2;
                    lblEtapa.setText("Etapa 2/2: baixando com " + numThreads + " thread(s)");
                });
                double tempoParalelo = rodarDownload(numThreads, totalMB);

                double aceleracao = tempoUnitario / tempoParalelo;
                SwingUtilities.invokeLater(() -> {
                    grafico.setTempo(2, tempoParalelo);
                    mostrarResultado(numThreads, tempoUnitario, tempoParalelo, aceleracao);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Erro na simulação: " + ex, "Erro", JOptionPane.ERROR_MESSAGE));
            } finally {
                SwingUtilities.invokeLater(() -> setOcupado(false));
            }
        }, "simulacao");
        simulacao.setDaemon(true);
        simulacao.start();
    }

    // A simulação em si é Main.executarDownload (a mesma do console); aqui a thread
    // "desenhista" do console é trocada por um javax.swing.Timer que atualiza a janela.
    private double rodarDownload(int numThreads, int totalMB) throws Exception {
        return Main.executarDownload(numThreads, totalMB, new ObservadorDownload() {
            @Override
            public void antesDeIniciar(GerenciadorVisual gerenciador) throws Exception {
                SwingUtilities.invokeAndWait(() -> montarLinhas(gerenciador, numThreads, totalMB));
            }

            @Override
            public void aoIniciar(GerenciadorVisual gerenciador, long t0) {
                SwingUtilities.invokeLater(() -> iniciarAtualizacao(t0));
            }

            @Override
            public void aoTerminar(GerenciadorVisual gerenciador) throws Exception {
                SwingUtilities.invokeAndWait(MainGUI.this::pararAtualizacao);
            }
        });
    }

    private void iniciarAtualizacao(long t0) {
        t0Atual = t0;
        nanosAnterior = System.nanoTime();
        timer.start();
    }

    private void pararAtualizacao() {
        timer.stop();
        atualizarTela();

        int maisLenta = 0, maisRapida = 0;
        for (int i = 1; i < tempoConclusao.length; i++) {
            if (tempoConclusao[i] > tempoConclusao[maisLenta]) maisLenta = i;
            if (tempoConclusao[i] < tempoConclusao[maisRapida]) maisRapida = i;
        }
        if (linhas.length > 1) {
            linhas[maisLenta].marcarGargalo();
            resumoGargalo = String.format(
                    "Gargalo: T%02d terminou por último (%s); a mais rápida, T%02d, em %s." +
                            " O arquivo só fica pronto quando a thread mais lenta termina.",
                    maisLenta + 1, Main.formatarTempo(tempoConclusao[maisLenta]),
                    maisRapida + 1, Main.formatarTempo(tempoConclusao[maisRapida]));
        } else {
            resumoGargalo = "";
        }
    }

    private void atualizarTela() {
        GerenciadorVisual g = gerenciadorAtual;
        if (g == null) return;

        int n = linhas.length;
        int[] progresso = new int[n];
        int[] porcentagem = new int[n];
        boolean[] concluido = new boolean[n];
        g.copiarEstado(progresso, porcentagem, concluido);

        long agora = System.nanoTime();
        double dt = (agora - nanosAnterior) / 1e9;
        nanosAnterior = agora;

        double decorrido = (System.currentTimeMillis() - t0Atual) / 1000.0;
        int totalBaixado = 0;
        double velocidadeTotal = 0;
        for (int i = 0; i < n; i++) {
            if (concluido[i] && Double.isNaN(tempoConclusao[i])) tempoConclusao[i] = decorrido;
            totalBaixado += progresso[i];

            // velocidade instantânea = MB baixados desde a última atualização / tempo decorrido,
            // com média móvel exponencial para o número não "tremer" na tela
            if (dt > 0) {
                double instantanea = (progresso[i] - progressoAnterior[i]) / dt;
                velocidades[i] = concluido[i] ? 0 : 0.5 * velocidades[i] + 0.5 * instantanea;
            }
            progressoAnterior[i] = progresso[i];
            velocidadeTotal += velocidades[i];

            linhas[i].atualizar(porcentagem[i], concluido[i], velocidades[i], tempoConclusao[i]);
        }

        int porcentagemGeral = (totalBaixado * 100) / totalMBAtual;
        barraTotal.setValor(porcentagemGeral, porcentagemGeral + "%");
        lblTotalMB.setText(totalBaixado + " / " + totalMBAtual + " MB");
        lblTotalVel.setText(porcentagemGeral >= 100 ? "—" : String.format("%.1f MB/s", velocidadeTotal));

        lblTempoEtapa.setText("Tempo: " + Main.formatarTempo(decorrido));
        grafico.setParcial(etapaAtual, decorrido);
    }

    private void mostrarResultado(int numThreads, double tempoUnitario, double tempoParalelo, double aceleracao) {
        lblEtapa.setText("Comparação concluída");
        String tempos = "Tempo com 1 thread: " + Main.formatarTempo(tempoUnitario) + "\n"
                + "Tempo com " + numThreads + " thread(s): " + Main.formatarTempo(tempoParalelo) + "\n"
                + (resumoGargalo.isEmpty() ? "" : resumoGargalo + "\n") + "\n";

        if (numThreads == 1) {
            lblSpeedup.setText("1,00x — igual");
            lblSpeedup.setForeground(CIANO);
            txtResultado.setText(tempos + "Mesma configuração das duas etapas — sem ganho nem perda.");
        } else if (aceleracao < 1.0) {
            lblSpeedup.setText(String.format("%.2fx MAIS LENTO", 1 / aceleracao));
            lblSpeedup.setForeground(VERMELHO);
            txtResultado.setText(tempos + String.format(
                    "%.2fx MAIS LENTO que 1 thread sozinha! Com %d threads, o engargalamento" +
                            " simulado de CPU superou o ganho de paralelismo.", (1 / aceleracao), numThreads));
        } else if (numThreads > 8 && aceleracao < numThreads * 0.5) {
            lblSpeedup.setText(String.format("%.2fx mais rápido", aceleracao));
            lblSpeedup.setForeground(AMARELO);
            txtResultado.setText(tempos + String.format(
                    "Mais rápido que 1 thread, mas MUITO abaixo do ideal (%dx)." +
                            " Sinal claro de retorno decrescente: threads demais competindo por CPU.", numThreads));
        } else {
            lblSpeedup.setText(String.format("%.2fx mais rápido", aceleracao));
            lblSpeedup.setForeground(VERDE);
            txtResultado.setText(tempos + String.format("%.2fx mais rápido que uma única thread.", aceleracao));
        }
        txtResultado.setCaretPosition(0);
    }

    // ------------------------------------------------------------------ análise: tempo x threads

    class PainelAnalise extends JPanel {
        static final double LIMITE_AVISO_S = 30;

        private final JTextField campoThreads = new JTextField("1, 2, 4, 6, 8, 10, 12, 16, 24, 32");
        private final JSpinner spinnerGB = new JSpinner(new SpinnerNumberModel(0.10, MIN_GB, 10.0, 0.05));
        private final JSpinner spinnerRepeticoes = new JSpinner(new SpinnerNumberModel(3, 1, 20, 1));
        private final JButton botaoVarrer = new JButton("Iniciar varredura");
        private final JButton botaoCancelar = new JButton("Cancelar");
        private final JLabel lblEstimativa = new JLabel(" ");

        private final JLabel lblProgresso = new JLabel("Nenhuma varredura em andamento.");
        private final JLabel lblTempo = new JLabel(" ");
        private final BarraProgresso barra = new BarraProgresso(AZUL, 22);

        private final GraficoTempoThreads graficoLinha = new GraficoTempoThreads();
        private final JLabel lblDestaque = new JLabel(" ");
        private final JTextArea txtConclusao = new JTextArea();

        private Thread varredura;
        private boolean cancelando;

        PainelAnalise() {
            super(new BorderLayout(0, 12));
            setBackground(FUNDO);
            setBorder(new EmptyBorder(14, 14, 14, 14));

            JPanel centro = new JPanel(new BorderLayout(12, 0));
            centro.setOpaque(false);
            centro.add(criarPainelGrafico(), BorderLayout.CENTER);
            centro.add(criarPainelConclusao(), BorderLayout.EAST);

            add(criarControles(), BorderLayout.NORTH);
            add(centro, BorderLayout.CENTER);

            botaoVarrer.addActionListener(e -> iniciar());
            botaoCancelar.addActionListener(e -> cancelar());
            campoThreads.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { atualizarEstimativa(); }
                public void removeUpdate(DocumentEvent e) { atualizarEstimativa(); }
                public void changedUpdate(DocumentEvent e) { atualizarEstimativa(); }
            });
            spinnerGB.addChangeListener(e -> atualizarEstimativa());
            spinnerRepeticoes.addChangeListener(e -> atualizarEstimativa());

            setCancelarAtivo(false);
            atualizarEstimativa();
        }

        // -------------------------------------------------------------- layout

        private JComponent criarControles() {
            JPanel cartao = cartao(new BorderLayout(0, 10));

            JLabel titulo = new JLabel("Análise: Tempo x Threads");
            titulo.setFont(new Font(FONTE, Font.BOLD, 22));
            titulo.setForeground(TEXTO);
            JLabel subtitulo = new JLabel("Varredura de vários números de threads para o MESMO arquivo — cada ponto é a média de várias repetições");
            subtitulo.setFont(new Font(FONTE, Font.PLAIN, 14));
            subtitulo.setForeground(TEXTO_SUAVE);

            JPanel textos = new JPanel(new GridLayout(2, 1));
            textos.setOpaque(false);
            textos.add(titulo);
            textos.add(subtitulo);

            campoThreads.setFont(new Font(FONTE, Font.PLAIN, 14));
            campoThreads.setPreferredSize(new Dimension(250, 30));
            campoThreads.setToolTipText("Valores de " + Main.MIN_THREADS + " a " + Main.MAX_THREADS
                    + " separados por vírgula; faixas como 2-6 também valem.");
            spinnerGB.setEditor(new JSpinner.NumberEditor(spinnerGB, "0.00"));
            estilizarSpinner(spinnerGB);
            estilizarSpinner(spinnerRepeticoes);
            spinnerRepeticoes.setToolTipText("Rodadas por ponto; o gráfico usa a média, reduzindo o ruído da rede simulada.");
            estilizarBotao(botaoVarrer, AZUL);
            estilizarBotao(botaoCancelar, VERMELHO);
            lblEstimativa.setFont(new Font(FONTE, Font.PLAIN, 13));
            lblEstimativa.setBorder(new EmptyBorder(0, 0, 0, 12));

            JPanel parametros = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            parametros.setOpaque(false);
            parametros.add(rotulo("Threads a testar:"));
            parametros.add(campoThreads);
            parametros.add(Box.createHorizontalStrut(8));
            parametros.add(rotulo("Arquivo (GB):"));
            parametros.add(spinnerGB);
            parametros.add(Box.createHorizontalStrut(8));
            parametros.add(rotulo("Repetições:"));
            parametros.add(spinnerRepeticoes);

            JPanel acoes = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            acoes.setOpaque(false);
            acoes.add(botaoVarrer);
            acoes.add(botaoCancelar);
            acoes.add(Box.createHorizontalStrut(8));
            acoes.add(lblEstimativa);

            lblProgresso.setFont(new Font(FONTE, Font.BOLD, 14));
            lblProgresso.setForeground(TEXTO);
            lblTempo.setFont(new Font(FONTE, Font.PLAIN, 13));
            lblTempo.setForeground(TEXTO_SUAVE);
            lblTempo.setBorder(new EmptyBorder(0, 12, 0, 4));

            JPanel textoProgresso = new JPanel(new BorderLayout(12, 0));
            textoProgresso.setOpaque(false);
            textoProgresso.add(lblProgresso, BorderLayout.WEST);
            textoProgresso.add(lblTempo, BorderLayout.EAST);

            JPanel progresso = new JPanel(new BorderLayout(0, 6));
            progresso.setOpaque(false);
            progresso.add(textoProgresso, BorderLayout.NORTH);
            progresso.add(barra, BorderLayout.CENTER);

            JPanel linhas = new JPanel(new BorderLayout(0, 10));
            linhas.setOpaque(false);
            linhas.add(parametros, BorderLayout.NORTH);
            linhas.add(acoes, BorderLayout.CENTER);
            linhas.add(progresso, BorderLayout.SOUTH);

            cartao.add(textos, BorderLayout.NORTH);
            cartao.add(linhas, BorderLayout.CENTER);
            return cartao;
        }

        private JComponent criarPainelGrafico() {
            JPanel cartao = cartao(new BorderLayout(0, 6));
            JLabel titulo = new JLabel("Tempo total x número de threads");
            titulo.setFont(new Font(FONTE, Font.BOLD, 16));
            titulo.setForeground(TEXTO);
            cartao.add(titulo, BorderLayout.NORTH);
            cartao.add(graficoLinha, BorderLayout.CENTER);
            return cartao;
        }

        private JComponent criarPainelConclusao() {
            JPanel cartao = cartao(new BorderLayout(0, 6));
            cartao.setPreferredSize(new Dimension(360, 200));

            JLabel titulo = new JLabel("Conclusão da varredura");
            titulo.setFont(new Font(FONTE, Font.BOLD, 16));
            titulo.setForeground(TEXTO);

            lblDestaque.setFont(new Font(FONTE, Font.BOLD, 22));
            lblDestaque.setForeground(TEXTO_SUAVE);

            txtConclusao.setEditable(false);
            txtConclusao.setFocusable(false);
            txtConclusao.setOpaque(false);
            txtConclusao.setLineWrap(true);
            txtConclusao.setWrapStyleWord(true);
            txtConclusao.setFont(new Font(FONTE, Font.PLAIN, 14));
            txtConclusao.setForeground(TEXTO);
            txtConclusao.setText("A conclusão aparece aqui quando a varredura terminar.");

            JScrollPane scroll = new JScrollPane(txtConclusao);
            scroll.setBorder(null);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

            JPanel texto = new JPanel(new BorderLayout(0, 8));
            texto.setOpaque(false);
            texto.add(lblDestaque, BorderLayout.NORTH);
            texto.add(scroll, BorderLayout.CENTER);

            cartao.add(titulo, BorderLayout.NORTH);
            cartao.add(texto, BorderLayout.CENTER);
            return cartao;
        }

        // -------------------------------------------------------------- estado dos controles

        void setControlesLiberados(boolean liberado) {
            campoThreads.setEnabled(liberado);
            spinnerGB.setEnabled(liberado);
            spinnerRepeticoes.setEnabled(liberado);
            botaoVarrer.setEnabled(liberado);
            botaoVarrer.setBackground(liberado ? AZUL : DESABILITADO);
            botaoVarrer.setToolTipText(liberado ? null : DICA_OCUPADO);
        }

        private void setCancelarAtivo(boolean ativo) {
            botaoCancelar.setEnabled(ativo);
            botaoCancelar.setBackground(ativo ? VERMELHO : DESABILITADO);
        }

        private int totalMB() {
            return (int) Math.round(((Number) spinnerGB.getValue()).doubleValue() * 1024);
        }

        private int repeticoes() {
            return ((Number) spinnerRepeticoes.getValue()).intValue();
        }

        // aceita "1, 2, 4, 8", "1 2 4" e faixas "2-6"; devolve os valores sem repetição, em ordem crescente
        private List<Integer> lerValoresThreads(String texto) {
            TreeSet<Integer> valores = new TreeSet<>();
            for (String parte : texto.trim().split("[,;\\s]+")) {
                if (parte.isEmpty()) continue;
                try {
                    int traco = parte.indexOf('-', 1);
                    if (traco > 0) {
                        int de = validarThreads(Integer.parseInt(parte.substring(0, traco)));
                        int ate = validarThreads(Integer.parseInt(parte.substring(traco + 1)));
                        if (de > ate) throw new IllegalArgumentException("Faixa invertida: \"" + parte + "\".");
                        for (int v = de; v <= ate; v++) valores.add(v);
                    } else {
                        valores.add(validarThreads(Integer.parseInt(parte)));
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Valor inválido: \"" + parte
                            + "\". Use inteiros separados por vírgula (ex: 1, 2, 4, 8) ou faixas (ex: 2-6).");
                }
            }
            if (valores.size() < 2) {
                throw new IllegalArgumentException("Informe pelo menos 2 valores diferentes de threads para traçar a curva.");
            }
            return new ArrayList<>(valores);
        }

        private int validarThreads(int v) {
            if (v < Main.MIN_THREADS || v > Main.MAX_THREADS) {
                throw new IllegalArgumentException("O número de threads deve ficar entre " + Main.MIN_THREADS
                        + " e " + Main.MAX_THREADS + " (valor informado: " + v + ").");
            }
            return v;
        }

        // estimativa de UMA rodada para cada ponto (sem multiplicar pelas repetições)
        private double[] estimativasPorPonto(List<Integer> valores, int totalMB) {
            double[] est = new double[valores.size()];
            for (int i = 0; i < est.length; i++) est[i] = DownloadThread.estimarSegundos(valores.get(i), totalMB);
            return est;
        }

        private void atualizarEstimativa() {
            List<Integer> valores;
            try {
                valores = lerValoresThreads(campoThreads.getText());
            } catch (IllegalArgumentException ex) {
                lblEstimativa.setForeground(VERMELHO);
                lblEstimativa.setText(ex.getMessage());
                lblEstimativa.setToolTipText(ex.getMessage());
                return;
            }
            int totalMB = totalMB();
            int repeticoes = repeticoes();
            double total = Arrays.stream(estimativasPorPonto(valores, totalMB)).sum() * repeticoes;

            lblEstimativa.setForeground(total > LIMITE_AVISO_S ? AMARELO : TEXTO_SUAVE);
            lblEstimativa.setText(String.format("%d pontos × %d repetições = %d rodadas · %d MB · estimativa ≈ %s",
                    valores.size(), repeticoes, valores.size() * repeticoes, totalMB, Main.formatarTempo(total)));
            lblEstimativa.setToolTipText(null);
        }

        // -------------------------------------------------------------- varredura

        private void iniciar() {
            try {
                spinnerGB.commitEdit();
                spinnerRepeticoes.commitEdit();
            } catch (ParseException ignored) {}

            List<Integer> valores;
            try {
                valores = lerValoresThreads(campoThreads.getText());
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(MainGUI.this, ex.getMessage(), "Valores de threads inválidos",
                        JOptionPane.WARNING_MESSAGE);
                campoThreads.requestFocusInWindow();
                return;
            }
            int totalMB = totalMB();
            int repeticoes = repeticoes();
            double[] estimativas = estimativasPorPonto(valores, totalMB);
            double estimativaTotal = Arrays.stream(estimativas).sum() * repeticoes;

            if (estimativaTotal > LIMITE_AVISO_S) {
                int maisDemorado = 0;
                for (int i = 1; i < estimativas.length; i++) {
                    if (estimativas[i] > estimativas[maisDemorado]) maisDemorado = i;
                }
                String mensagem = String.format(
                        "A varredura deve levar cerca de %s (%d pontos × %d repetições = %d rodadas, arquivo de %d MB).%n%n"
                                + "O ponto mais demorado é o de %d thread(s), ≈ %s por rodada.%n"
                                + "Para encurtar, diminua o tamanho do arquivo, as repetições ou a lista de threads.",
                        Main.formatarTempo(estimativaTotal), valores.size(), repeticoes, valores.size() * repeticoes,
                        totalMB, valores.get(maisDemorado), Main.formatarTempo(estimativas[maisDemorado]));
                Object[] opcoes = { "Rodar mesmo assim", "Ajustar parâmetros" };
                int escolha = JOptionPane.showOptionDialog(MainGUI.this, mensagem, "Varredura longa",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opcoes, opcoes[1]);
                if (escolha != 0) return;
            }

            setOcupado(true);
            setCancelarAtivo(true);
            cancelando = false;
            graficoLinha.reiniciar(valores);
            barra.setValor(0, "0%");
            lblProgresso.setText("Preparando a varredura…");
            lblTempo.setText(" ");
            lblDestaque.setText("…");
            lblDestaque.setForeground(TEXTO_SUAVE);
            txtConclusao.setText("Aguardando a varredura terminar.");

            varredura = new Thread(() -> executarVarredura(valores, totalMB, repeticoes, estimativas), "varredura");
            varredura.setDaemon(true);
            varredura.start();
        }

        private void cancelar() {
            if (varredura == null) return;
            cancelando = true;
            varredura.interrupt();
            setCancelarAtivo(false);
            lblProgresso.setText("Cancelando…");
        }

        // roda FORA da EDT: só mede tempos; a interface é atualizada via invokeLater uma vez por rodada
        private void executarVarredura(List<Integer> valores, int totalMB, int repeticoes, double[] estimativas) {
            int pontos = valores.size();
            int totalRodadas = pontos * repeticoes;
            double estimativaTotal = Arrays.stream(estimativas).sum() * repeticoes;
            double[] medias = new double[pontos];
            Arrays.fill(medias, Double.NaN);

            long inicio = System.nanoTime();
            double estimadoConcluido = 0;
            boolean interrompida = false;
            try {
                for (int i = 0; i < pontos; i++) {
                    int n = valores.get(i);
                    int ponto = i;
                    double soma = 0;
                    for (int r = 0; r < repeticoes; r++) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();

                        String texto = String.format("Testando %d thread(s) (%d/%d) — repetição %d/%d…",
                                n, i + 1, pontos, r + 1, repeticoes);
                        int pct = (i * repeticoes + r) * 100 / totalRodadas;
                        double decorrido = (System.nanoTime() - inicio) / 1e9;
                        // corrige o que falta pela razão real/estimado observada até aqui
                        double restante = (estimativaTotal - estimadoConcluido)
                                * (estimadoConcluido > 0 ? decorrido / estimadoConcluido : 1);
                        SwingUtilities.invokeLater(() -> mostrarProgresso(texto, pct, decorrido, restante, ponto));

                        soma += Main.rodarDownloadSilencioso(n, totalMB);
                        estimadoConcluido += estimativas[i];
                    }
                    double media = soma / repeticoes;
                    medias[i] = media;
                    SwingUtilities.invokeLater(() -> graficoLinha.setTempo(ponto, media));
                }
            } catch (InterruptedException e) {
                interrompida = true;
            } catch (RuntimeException ex) {
                interrompida = true;
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(MainGUI.this,
                        "Erro na varredura: " + ex, "Erro", JOptionPane.ERROR_MESSAGE));
            }

            boolean cancelada = interrompida;
            double duracao = (System.nanoTime() - inicio) / 1e9;
            SwingUtilities.invokeLater(() -> finalizar(valores, medias, totalMB, repeticoes, cancelada, duracao));
        }

        private void mostrarProgresso(String texto, int pct, double decorrido, double restante, int ponto) {
            if (cancelando) return;
            lblProgresso.setText(texto);
            barra.setValor(pct, pct + "%");
            lblTempo.setText("Decorrido: " + Main.formatarTempo(decorrido) + " · restante ≈ " + Main.formatarTempo(restante));
            graficoLinha.setEmTeste(ponto);
        }

        private void finalizar(List<Integer> valores, double[] medias, int totalMB, int repeticoes,
                               boolean cancelada, double duracao) {
            varredura = null;
            cancelando = false;
            setCancelarAtivo(false);
            graficoLinha.setEmTeste(-1);

            int concluidos = (int) Arrays.stream(medias).filter(t -> !Double.isNaN(t)).count();
            if (cancelada) {
                lblProgresso.setText(String.format("Varredura interrompida — %d de %d pontos concluídos.",
                        concluidos, valores.size()));
            } else {
                lblProgresso.setText("Varredura concluída — " + valores.size() + " pontos testados.");
                barra.setValor(100, "100%");
            }
            lblTempo.setText("Duração da varredura: " + Main.formatarTempo(duracao));

            mostrarConclusao(valores, medias, totalMB, repeticoes, cancelada);
            txtConclusao.setCaretPosition(0);
            setOcupado(false);
        }

        // -------------------------------------------------------------- conclusão

        private void mostrarConclusao(List<Integer> valores, double[] medias, int totalMB, int repeticoes, boolean cancelada) {
            List<Integer> ns = new ArrayList<>();
            List<Double> ts = new ArrayList<>();
            for (int i = 0; i < medias.length; i++) {
                if (!Double.isNaN(medias[i])) {
                    ns.add(valores.get(i));
                    ts.add(medias[i]);
                }
            }

            StringBuilder texto = new StringBuilder(String.format("Arquivo: %d MB · média de %d repetição(ões) por ponto%s%n",
                    totalMB, repeticoes, cancelada ? " · resultado PARCIAL" : ""));
            for (int k = 0; k < ns.size(); k++) {
                texto.append(k == 0 ? "" : " · ").append(ns.get(k)).append(" → ").append(Main.formatarTempo(ts.get(k)));
            }
            texto.append("\n\n");

            if (ns.size() < 2) {
                lblDestaque.setText("Sem dados suficientes");
                lblDestaque.setForeground(TEXTO_SUAVE);
                txtConclusao.setText(texto + "A varredura parou antes de concluir 2 pontos — não há curva para analisar.");
                return;
            }

            int iMin = 0;
            for (int k = 1; k < ts.size(); k++) if (ts.get(k) < ts.get(iMin)) iMin = k;
            int ultimo = ns.size() - 1;
            int nMin = ns.get(iMin), nBase = ns.get(0), nMax = ns.get(ultimo);
            double tMin = ts.get(iMin), tBase = ts.get(0), tMax = ts.get(ultimo);
            String referencia = nBase == 1 ? "1 thread" : nBase + " threads (menor valor testado)";

            lblDestaque.setText(String.format("Mínimo: %d thread(s) · %s", nMin, Main.formatarTempo(tMin)));
            lblDestaque.setForeground(ROXO);

            if (iMin == 0) {
                texto.append(String.format("Paralelizar não compensou nesta faixa: o menor tempo foi com %s (%s)."
                                + " Todos os valores maiores ficaram mais lentos — a disputa de CPU/lock entre as threads"
                                + " anulou o ganho de dividir o arquivo.",
                        referencia, Main.formatarTempo(tMin)));
            } else if (iMin == ultimo) {
                int nAnterior = ns.get(ultimo - 1);
                double ganhoFinal = 1 - tMin / ts.get(ultimo - 1);
                texto.append(String.format("O tempo caiu em toda a faixa testada: o menor foi com o maior valor, %d threads (%s),"
                                + " %.2fx mais rápido que %s (o ideal seria %sx).",
                        nMin, Main.formatarTempo(tMin), tBase / tMin, referencia, fator((double) nMin / nBase)));
                if (ganhoFinal < 0.05) {
                    texto.append(String.format(" Mas a curva já está achatando: de %d para %d threads o ganho foi de só %.0f%%"
                            + " — perto do ponto em que a competição entre as threads passa a pesar mais que o paralelismo.",
                            nAnterior, nMin, ganhoFinal * 100));
                } else {
                    texto.append(" Ainda pode haver ganho com mais threads — vale estender a faixa para achar o ponto de virada.");
                }
            } else {
                texto.append(String.format("O tempo caiu até %d threads (%s, %.2fx mais rápido que %s — o ideal seria %sx)."
                                + " A partir daí piorou por disputa de CPU/lock entre elas: com %d threads levou %s,"
                                + " %.0f%% mais lento que o ponto ótimo.",
                        nMin, Main.formatarTempo(tMin), tBase / tMin, referencia, fator((double) nMin / nBase),
                        nMax, Main.formatarTempo(tMax), (tMax / tMin - 1) * 100));
                if (tMax > tBase) {
                    texto.append(String.format(" Com %d threads ficou até MAIS LENTO que %s!", nMax, referencia));
                }
            }

            double idealMax = tBase * nBase / nMax;
            double eficiencia = (tBase / tMax) / ((double) nMax / nBase);
            texto.append(String.format("%n%nContra o ideal linear: com %d threads o tempo teórico seria %s, mas o real foi %s"
                            + " (%.1fx acima do ideal; eficiência de %.0f%%).",
                    nMax, Main.formatarTempo(idealMax), Main.formatarTempo(tMax), tMax / idealMax, eficiencia * 100));

            // pontos que fogem da tendência (descendo até o mínimo, subindo depois) por mais de 5%
            boolean oscilou = false;
            for (int k = 1; k < ts.size(); k++) {
                boolean desvio = k <= iMin ? ts.get(k) > ts.get(k - 1) * 1.05 : ts.get(k) < ts.get(k - 1) * 0.95;
                if (desvio) oscilou = true;
            }
            if (oscilou) {
                texto.append(String.format("%n%nA curva oscilou fora da tendência em algum trecho (ruído da rede simulada)%s.",
                        repeticoes < 5 ? "; aumentar as repetições suaviza o resultado" : ""));
            }

            txtConclusao.setText(texto.toString());
        }

        private String fator(double f) {
            return Math.abs(f - Math.rint(f)) < 1e-9 ? String.format("%.0f", f) : String.format("%.1f", f);
        }
    }

    // ------------------------------------------------------------------ utilitários

    private void atualizarInfoArquivo() {
        double tamanhoGB = ((Number) spinnerTamanho.getValue()).doubleValue();
        int totalMB = (int) Math.round(tamanhoGB * 1024);
        if (totalMB > 3000) {
            lblArquivo.setForeground(AMARELO);
            lblArquivo.setText(String.format("%d MB — aviso: a etapa de 1 thread pode demorar bastante", totalMB));
        } else {
            lblArquivo.setForeground(TEXTO_SUAVE);
            lblArquivo.setText(String.format("Arquivo: %.2f GB (%d MB)", tamanhoGB, totalMB));
        }
    }

    private void setOcupado(boolean ocupado) {
        setControlesHabilitados(!ocupado);
        painelAnalise.setControlesLiberados(!ocupado);
    }

    private void setControlesHabilitados(boolean habilitado) {
        spinnerTamanho.setEnabled(habilitado);
        spinnerThreads.setEnabled(habilitado);
        botaoIniciar.setEnabled(habilitado);
        botaoIniciar.setBackground(habilitado ? AZUL : DESABILITADO);
        botaoIniciar.setToolTipText(habilitado ? null : DICA_OCUPADO);
    }

    private static JPanel cartao(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setBackground(CARTAO);
        p.setBorder(new CompoundBorder(new LineBorder(BORDA), new EmptyBorder(12, 14, 12, 14)));
        return p;
    }

    private static JLabel rotulo(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(new Font(FONTE, Font.PLAIN, 14));
        l.setForeground(TEXTO);
        return l;
    }

    private static void estilizarBotao(JButton b, Color cor) {
        b.setFont(new Font(FONTE, Font.BOLD, 14));
        b.setBackground(cor);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(8, 18, 8, 18));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private static void estilizarSpinner(JSpinner s) {
        s.setFont(new Font(FONTE, Font.PLAIN, 14));
        s.setPreferredSize(new Dimension(90, 30));
    }

    private static void fixarLargura(JComponent comp, int largura) {
        Dimension d = comp.getPreferredSize();
        comp.setPreferredSize(new Dimension(largura, d.height));
    }

    static Color corDaThread(int i) {
        float matiz = (0.53f + i * 0.618034f) % 1f;
        return Color.getHSBColor(matiz, 0.70f, 0.82f);
    }

    // ------------------------------------------------------------------ componentes

    static class LinhaThread {
        final JLabel nome, faixa, status, porcentagem, velocidade;
        final BarraProgresso barra;
        private Boolean ultimoConcluido;

        LinhaThread(int id, EstadoPedaco p, int alturaBarra, int tamFonte) {
            Color cor = corDaThread(id);
            Font fonte = new Font(FONTE, Font.PLAIN, tamFonte);
            Font fonteNegrito = new Font(FONTE, Font.BOLD, tamFonte);

            nome = new JLabel(String.format("T%02d", id + 1), new IconeCor(cor, tamFonte), SwingConstants.LEFT);
            nome.setFont(fonteNegrito);
            nome.setForeground(TEXTO);
            nome.setIconTextGap(8);

            faixa = new JLabel(String.format("%d - %d", p.byteInicio, p.byteFim));
            faixa.setFont(fonte);
            faixa.setForeground(TEXTO_SUAVE);

            status = new JLabel();
            status.setOpaque(true);
            status.setHorizontalAlignment(SwingConstants.CENTER);
            status.setFont(new Font(FONTE, Font.BOLD, tamFonte - 2));
            status.setBorder(new EmptyBorder(2, 8, 2, 8));

            barra = new BarraProgresso(cor, alturaBarra);

            porcentagem = new JLabel("0%");
            porcentagem.setFont(fonteNegrito);
            porcentagem.setForeground(TEXTO);
            porcentagem.setHorizontalAlignment(SwingConstants.RIGHT);

            velocidade = new JLabel("0,0 MB/s");
            velocidade.setFont(fonte);
            velocidade.setForeground(TEXTO);
            velocidade.setHorizontalAlignment(SwingConstants.RIGHT);

            atualizar(0, false, 0, Double.NaN);
            fixarLargura(status, 96);
            fixarLargura(porcentagem, 44);
            fixarLargura(velocidade, 150);
        }

        void atualizar(int pct, boolean concluido, double mbPorSegundo, double segundosConclusao) {
            barra.setValor(pct, null);
            porcentagem.setText(pct + "%");
            velocidade.setText(concluido
                    ? "terminou em " + Main.formatarTempo(segundosConclusao)
                    : String.format("%.1f MB/s", mbPorSegundo));
            velocidade.setForeground(concluido ? TEXTO_SUAVE : TEXTO);
            if (ultimoConcluido == null || ultimoConcluido != concluido) {
                ultimoConcluido = concluido;
                status.setText(concluido ? "CONCLUÍDO" : "RODANDO");
                status.setBackground(concluido ? new Color(0xDCFCE7) : new Color(0xFEF3C7));
                status.setForeground(concluido ? VERDE : AMARELO);
            }
        }

        void marcarGargalo() {
            status.setText("GARGALO");
            status.setBackground(new Color(0xFEE2E2));
            status.setForeground(VERMELHO);
            velocidade.setForeground(VERMELHO);
            velocidade.setFont(velocidade.getFont().deriveFont(Font.BOLD));
        }
    }

    static class IconeCor implements Icon {
        private final Color cor;
        private final int tamanho;

        IconeCor(Color cor, int tamanho) {
            this.cor = cor;
            this.tamanho = tamanho;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(cor);
            g2.fillRoundRect(x, y, tamanho, tamanho, 4, 4);
            g2.dispose();
        }

        public int getIconWidth() { return tamanho; }
        public int getIconHeight() { return tamanho; }
    }

    static class BarraProgresso extends JComponent {
        private final Color cor;
        private int porcentagem;
        private String texto;

        BarraProgresso(Color cor, int altura) {
            this.cor = cor;
            setPreferredSize(new Dimension(220, altura));
            setMinimumSize(new Dimension(80, altura));
        }

        void setValor(int porcentagem, String texto) {
            if (this.porcentagem == porcentagem && java.util.Objects.equals(this.texto, texto)) return;
            this.porcentagem = porcentagem;
            this.texto = texto;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            RoundRectangle2D forma = new RoundRectangle2D.Float(0, 0, w, h, h, h);
            int preenchido = (int) Math.round(w * porcentagem / 100.0);

            g2.setColor(TRILHO);
            g2.fill(forma);
            g2.clip(forma);
            g2.setColor(cor);
            g2.fillRect(0, 0, preenchido, h);

            if (texto != null) {
                g2.setFont(new Font(FONTE, Font.BOLD, Math.max(11, (int) (h * 0.5))));
                FontMetrics fm = g2.getFontMetrics();
                int tx = (w - fm.stringWidth(texto)) / 2;
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();

                Shape clipBase = g2.getClip();
                g2.clipRect(0, 0, preenchido, h);
                g2.setColor(Color.WHITE);
                g2.drawString(texto, tx, ty);
                g2.setClip(clipBase);
                g2.clipRect(preenchido, 0, w - preenchido, h);
                g2.setColor(TEXTO);
                g2.drawString(texto, tx, ty);
            }
            g2.dispose();
        }
    }

    static class GraficoComparacao extends JComponent {
        private double tempo1 = Double.NaN, tempoN = Double.NaN;
        private double parcial;
        private int etapa;
        private int numThreads = 1;

        GraficoComparacao() {
            setPreferredSize(new Dimension(400, 110));
        }

        void reiniciar(int numThreads) {
            this.numThreads = numThreads;
            tempo1 = tempoN = Double.NaN;
            parcial = 0;
            etapa = 0;
            repaint();
        }

        void setTempo(int etapa, double segundos) {
            if (etapa == 1) tempo1 = segundos; else tempoN = segundos;
            repaint();
        }

        void setParcial(int etapa, double segundos) {
            this.etapa = etapa;
            this.parcial = segundos;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            boolean final1 = !Double.isNaN(tempo1);
            boolean finalN = !Double.isNaN(tempoN);
            double v1 = final1 ? tempo1 : (etapa == 1 ? parcial : 0);
            double vN = finalN ? tempoN : (etapa == 2 ? parcial : 0);
            double maximo = Math.max(Math.max(v1, vN), 0.001);

            int margemEsq = 110, margemDir = 150;
            int largura = Math.max(20, getWidth() - margemEsq - margemDir);
            int alturaBarra = 28;
            int y1 = 14, y2 = y1 + alturaBarra + 16;

            desenharLinha(g2, "1 thread", v1, final1, v1 > 0 || etapa >= 1, AZUL, y1, alturaBarra, margemEsq, largura, maximo);
            desenharLinha(g2, numThreads + " thread(s)", vN, finalN, etapa == 2 || finalN, ROXO, y2, alturaBarra, margemEsq, largura, maximo);
            g2.dispose();
        }

        private void desenharLinha(Graphics2D g2, String nome, double valor, boolean finalizado, boolean iniciado,
                                   Color cor, int y, int h, int x0, int largura, double maximo) {
            g2.setFont(new Font(FONTE, Font.BOLD, 14));
            FontMetrics fm = g2.getFontMetrics();
            int baseTexto = y + (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.setColor(TEXTO);
            g2.drawString(nome, 0, baseTexto);

            g2.setColor(TRILHO);
            g2.fillRoundRect(x0, y, largura, h, 8, 8);

            int w = (int) Math.round(largura * (valor / maximo));
            if (w > 0) {
                g2.setColor(finalizado ? cor : new Color(cor.getRed(), cor.getGreen(), cor.getBlue(), 120));
                g2.fillRoundRect(x0, y, w, h, 8, 8);
            }

            String rotulo = !iniciado ? "aguardando" : Main.formatarTempo(valor) + (finalizado ? "" : " (rodando)");
            g2.setFont(new Font(FONTE, finalizado ? Font.BOLD : Font.PLAIN, 14));
            g2.setColor(finalizado ? TEXTO : TEXTO_SUAVE);
            g2.drawString(rotulo, x0 + largura + 10, baseTexto);
        }
    }

    static class GraficoTempoThreads extends JComponent {
        private int[] ns = new int[0];          // valores de threads, em ordem crescente
        private double[] tempos = new double[0]; // média medida de cada ponto (NaN = ainda não medido)
        private Point[] pontosTela = new Point[0];
        private int emTeste = -1;

        GraficoTempoThreads() {
            setPreferredSize(new Dimension(500, 300));
            setToolTipText(""); // habilita getToolTipText(MouseEvent)
        }

        void reiniciar(List<Integer> valores) {
            ns = valores.stream().mapToInt(Integer::intValue).toArray();
            tempos = new double[ns.length];
            Arrays.fill(tempos, Double.NaN);
            pontosTela = new Point[ns.length];
            emTeste = -1;
            repaint();
        }

        void setTempo(int indice, double segundos) {
            tempos[indice] = segundos;
            repaint();
        }

        void setEmTeste(int indice) {
            emTeste = indice;
            repaint();
        }

        private boolean temIdeal() {
            return ns.length > 1 && !Double.isNaN(tempos[0]);
        }

        // ideal linear a partir do menor valor testado: T(n0) * n0 / N (com n0 = 1, T(1)/N)
        private double ideal(double n) {
            return tempos[0] * ns[0] / n;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            for (int i = 0; i < pontosTela.length; i++) {
                Point p = pontosTela[i];
                if (p != null && p.distance(e.getPoint()) <= 10) {
                    String ideal = temIdeal() && i > 0 ? " (ideal linear: " + Main.formatarTempo(ideal(ns[i])) + ")" : "";
                    return ns[i] + " thread(s): " + Main.formatarTempo(tempos[i]) + ideal;
                }
            }
            return null;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Font fonteEixo = new Font(FONTE, Font.PLAIN, 12);
            Font fonteEixoNegrito = new Font(FONTE, Font.BOLD, 12);

            if (ns.length == 0) {
                g2.setFont(new Font(FONTE, Font.PLAIN, 14));
                g2.setColor(TEXTO_SUAVE);
                String msg = "Configure a varredura e clique em Iniciar varredura.";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
                g2.dispose();
                return;
            }

            int x0 = 64, x1 = w - 24, yTopo = 52, yBase = h - 48;
            if (x1 - x0 < 60 || yBase - yTopo < 60) {
                g2.dispose();
                return;
            }

            int nMin = ns[0], nMax = ns[ns.length - 1];
            double spanN = Math.max(1, nMax - nMin);
            double maxT = 0;
            int medidos = 0, iMin = -1;
            for (int i = 0; i < tempos.length; i++) {
                if (Double.isNaN(tempos[i])) continue;
                medidos++;
                maxT = Math.max(maxT, tempos[i]);
                if (iMin < 0 || tempos[i] < tempos[iMin]) iMin = i;
            }
            double passoY = passoRedondo((maxT > 0 ? maxT : 1) / 5);
            double topoEscala = Math.max(passoY, Math.ceil(maxT * 1.08 / passoY) * passoY); // folga para o rótulo do mínimo

            int padX = 18;
            DoubleUnaryOperator px = n -> x0 + padX + (n - nMin) / spanN * (x1 - x0 - 2 * padX);
            DoubleUnaryOperator py = t -> yBase - t / topoEscala * (yBase - yTopo);

            // grade e rótulos do eixo Y
            g2.setFont(fonteEixo);
            FontMetrics fm = g2.getFontMetrics();
            int linhasGrade = (int) Math.round(topoEscala / passoY);
            for (int k = 0; k <= linhasGrade; k++) {
                double v = k * passoY;
                int y = (int) Math.round(py.applyAsDouble(v));
                g2.setColor(TRILHO);
                g2.drawLine(x0, y, x1, y);
                String s = formatarEixo(v, passoY);
                g2.setColor(TEXTO_SUAVE);
                g2.drawString(s, x0 - 8 - fm.stringWidth(s), y + fm.getAscent() / 2 - 1);
            }
            g2.setColor(TEXTO_SUAVE);
            g2.drawString("Tempo total (s)", 4, yTopo - 14);

            // eixo X com um tique por valor testado (pula rótulos que encostariam no anterior)
            g2.drawLine(x0, yBase, x1, yBase);
            int fimUltimoRotulo = Integer.MIN_VALUE;
            for (int i = 0; i < ns.length; i++) {
                int x = (int) Math.round(px.applyAsDouble(ns[i]));
                boolean atual = i == emTeste;
                g2.setColor(atual ? AZUL : TEXTO_SUAVE);
                g2.drawLine(x, yBase, x, yBase + 4);
                g2.setFont(atual ? fonteEixoNegrito : fonteEixo);
                String s = String.valueOf(ns[i]);
                int sw = g2.getFontMetrics().stringWidth(s);
                if (x - sw / 2 > fimUltimoRotulo + 4 || atual) {
                    g2.drawString(s, x - sw / 2, yBase + 6 + fm.getAscent());
                    fimUltimoRotulo = x + sw / 2;
                }
            }
            g2.setFont(fonteEixo);
            g2.setColor(TEXTO_SUAVE);
            String tituloX = "Número de threads";
            g2.drawString(tituloX, (x0 + x1 - fm.stringWidth(tituloX)) / 2, h - 6);

            // referência: speedup ideal/linear (hipérbole amostrada densamente para ficar suave)
            if (temIdeal()) {
                Path2D curvaIdeal = new Path2D.Double();
                int amostras = Math.max(40, (x1 - x0) / 4);
                for (int k = 0; k <= amostras; k++) {
                    double n = nMin + spanN * k / amostras;
                    double x = px.applyAsDouble(n), y = py.applyAsDouble(ideal(n));
                    if (k == 0) curvaIdeal.moveTo(x, y); else curvaIdeal.lineTo(x, y);
                }
                g2.setColor(TEXTO_SUAVE);
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{ 6f, 5f }, 0f));
                g2.draw(curvaIdeal);
            }

            // curva real
            Path2D curvaReal = new Path2D.Double();
            boolean comecou = false;
            Arrays.fill(pontosTela, null);
            for (int i = 0; i < ns.length; i++) {
                if (Double.isNaN(tempos[i])) continue;
                Point p = new Point((int) Math.round(px.applyAsDouble(ns[i])), (int) Math.round(py.applyAsDouble(tempos[i])));
                pontosTela[i] = p;
                if (!comecou) curvaReal.moveTo(p.x, p.y); else curvaReal.lineTo(p.x, p.y);
                comecou = true;
            }
            g2.setColor(AZUL);
            g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(curvaReal);
            g2.setStroke(new BasicStroke(1f));

            boolean destacarMinimo = medidos >= 2;
            for (int i = 0; i < pontosTela.length; i++) {
                if (pontosTela[i] != null && !(destacarMinimo && i == iMin)) marcador(g2, pontosTela[i], AZUL, 5);
            }

            if (destacarMinimo) {
                Point p = pontosTela[iMin];
                marcador(g2, p, ROXO, 7);

                String rotulo = String.format("Menor tempo: %d thread(s) · %s", ns[iMin], Main.formatarTempo(tempos[iMin]));
                g2.setFont(fonteEixoNegrito);
                FontMetrics fmN = g2.getFontMetrics();
                int bw = fmN.stringWidth(rotulo) + 16, bh = 22;
                int bx = Math.max(x0, Math.min(p.x - bw / 2, x1 - bw));
                int by = p.y - 14 - bh;
                if (by < yTopo - 8) by = p.y + 14; // sem espaço acima: rótulo vai para baixo do ponto
                g2.setColor(CARTAO);
                g2.fillRoundRect(bx, by, bw, bh, 10, 10);
                g2.setColor(ROXO);
                g2.drawRoundRect(bx, by, bw, bh, 10, 10);
                g2.drawString(rotulo, bx + 8, by + (bh - fmN.getHeight()) / 2 + fmN.getAscent());
            }

            desenharLegenda(g2, fonteEixo, x1);
            g2.dispose();
        }

        private void desenharLegenda(Graphics2D g2, Font fonte, int xDireita) {
            g2.setFont(fonte);
            FontMetrics fm = g2.getFontMetrics();
            String[] textos = {
                    "Tempo real (média)",
                    ns[0] == 1 ? "Ideal linear: T(1)/N" : "Ideal linear a partir de " + ns[0] + " threads",
                    "Menor tempo"
            };
            int amostra = 22, respiro = 6, entre = 18;
            int total = 0;
            for (String t : textos) total += amostra + respiro + fm.stringWidth(t) + entre;
            total -= entre;

            int x = Math.max(4, xDireita - total);
            int yMeio = 12;
            int base = yMeio + fm.getAscent() / 2 - 1;
            for (int k = 0; k < textos.length; k++) {
                if (k == 0) {
                    g2.setColor(AZUL);
                    g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(x, yMeio, x + amostra, yMeio);
                } else if (k == 1) {
                    g2.setColor(TEXTO_SUAVE);
                    g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{ 6f, 5f }, 0f));
                    g2.drawLine(x, yMeio, x + amostra, yMeio);
                } else {
                    marcador(g2, new Point(x + amostra / 2, yMeio), ROXO, 6);
                }
                g2.setStroke(new BasicStroke(1f));
                g2.setColor(TEXTO);
                g2.drawString(textos[k], x + amostra + respiro, base);
                x += amostra + respiro + fm.stringWidth(textos[k]) + entre;
            }
        }

        private static void marcador(Graphics2D g2, Point p, Color cor, int raio) {
            g2.setColor(CARTAO);
            g2.fillOval(p.x - raio - 2, p.y - raio - 2, 2 * raio + 4, 2 * raio + 4);
            g2.setColor(cor);
            g2.fillOval(p.x - raio, p.y - raio, 2 * raio, 2 * raio);
        }

        // passo "redondo" (1, 2 ou 5 × 10^k) mais próximo acima de bruto
        private static double passoRedondo(double bruto) {
            double escala = Math.pow(10, Math.floor(Math.log10(bruto)));
            double f = bruto / escala;
            double redondo = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
            return redondo * escala;
        }

        private static String formatarEixo(double v, double passo) {
            if (passo >= 1) return String.format("%.0f", v);
            if (passo >= 0.1) return String.format("%.1f", v);
            return String.format("%.2f", v);
        }
    }
}
