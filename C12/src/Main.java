import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class Main {

    static final String RESET   = "\u001B[0m";
    static final String BOLD    = "\u001B[1m";
    static final String CIANO   = "\u001B[36m";
    static final String VERDE   = "\u001B[32m";
    static final String AMARELO = "\u001B[33m";
    static final String VERMELHO= "\u001B[31m";
    static final String AZUL    = "\u001B[34m";
    static final String MAGENTA = "\u001B[35m";

    static final String[] CORES_THREAD = { CIANO, MAGENTA, AZUL, AMARELO, VERDE };

    static final int MIN_THREADS = 1;
    static final int MAX_THREADS = 64;

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

        Scanner scanner = new Scanner(System.in);
        scanner.useLocale(Locale.US);

        System.out.println(BOLD + CIANO + "=== MONITOR VISUAL DE PARALELISMO (THREADS) ===" + RESET);

        double tamanhoGB = -1;
        while (tamanhoGB <= 0) {
            System.out.print("Tamanho do arquivo em GB (ex: 0.5, 1, 10): ");
            if (scanner.hasNextDouble()) tamanhoGB = scanner.nextDouble();
            else scanner.next();
        }
        int totalMB = (int) Math.round(tamanhoGB * 1024);

        int numThreads = 0;
        while (numThreads < MIN_THREADS || numThreads > MAX_THREADS) {
            System.out.print("Dividir em quantas Threads? (" + MIN_THREADS + " a " + MAX_THREADS + "): ");
            if (scanner.hasNextInt()) numThreads = scanner.nextInt();
            else scanner.next();
        }

        System.out.println();
        System.out.println(String.format("Arquivo: %.2f GB (%d MB)", tamanhoGB, totalMB));
        if (totalMB > 3000) {
            System.out.println(AMARELO + "Aviso: arquivo grande — a etapa de 1 thread pode demorar bastante" +
                    " (tempo real, proporcional ao tamanho)." + RESET);
        }
        System.out.println();

        System.out.println(BOLD + "Etapa 1/2: baixando com 1 thread (referencia)" + RESET);
        double tempoUnitario = rodarDownload(1, totalMB);

        System.out.println();

        System.out.println(BOLD + "Etapa 2/2: baixando com " + numThreads + " thread(s)" + RESET);
        double tempoParalelo = rodarDownload(numThreads, totalMB);

        double aceleracao = tempoUnitario / tempoParalelo;

        System.out.println();
        System.out.println(BOLD + "=== RESULTADO FINAL ===" + RESET);
        System.out.println("Tempo com 1 thread  : " + formatarTempo(tempoUnitario));
        System.out.println("Tempo com " + numThreads + " thread(s): " + formatarTempo(tempoParalelo));

        if (numThreads == 1) {
            System.out.println(CIANO + "Mesma configuração das duas etapas — sem ganho nem perda." + RESET);
        } else if (aceleracao < 1.0) {
            System.out.println(VERMELHO + String.format(
                    "Resultado: %.2fx MAIS LENTO que 1 thread sozinha! Com %d threads, o engargalamento" +
                            " simulado de CPU superou o ganho de paralelismo.", (1 / aceleracao), numThreads) + RESET);
        } else if (numThreads > 8 && aceleracao < numThreads * 0.5) {
            System.out.println(AMARELO + String.format(
                    "Resultado: %.2fx mais rápido — mais rápido que 1 thread, mas MUITO abaixo do ideal (%dx)." +
                            " Sinal claro de retorno decrescente: threads demais competindo por CPU.", aceleracao, numThreads) + RESET);
        } else {
            System.out.println(VERDE + String.format("Resultado: %.2fx mais rápido que uma única thread.", aceleracao) + RESET);
        }
        scanner.close();
    }

    private static double rodarDownload(int numThreads, int totalMB) throws Exception {
        return executarDownload(numThreads, totalMB, new ObservadorDownload() {
            private Thread desenhista;

            @Override
            public void aoIniciar(GerenciadorVisual gerenciador, long t0) {
                desenhista = new Thread(() -> {
                    while (!gerenciador.todosConcluidos()) {
                        gerenciador.imprimirTela();
                        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                    }
                });
                desenhista.start();
            }

            @Override
            public void aoTerminar(GerenciadorVisual gerenciador) throws InterruptedException {
                desenhista.join();
                gerenciador.imprimirTela();
            }
        });
    }

    // Só mede o tempo total, sem desenhar nada — usado na varredura Tempo x Threads da GUI.
    // Interromper a thread que chama cancela a rodada (lança InterruptedException).
    static double rodarDownloadSilencioso(int numThreads, int totalMB) throws InterruptedException {
        try {
            return executarDownload(numThreads, totalMB, ObservadorDownload.NENHUM);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e); // o observador vazio não lança nada
        }
    }

    // Núcleo comum da simulação (console, GUI e varredura): divide o arquivo em pedaços, dispara uma
    // DownloadThread por pedaço e mede o tempo até todas terminarem. O observador decide o que mostrar.
    static double executarDownload(int numThreads, int totalMB, ObservadorDownload observador) throws Exception {
        GerenciadorVisual gerenciador = new GerenciadorVisual(numThreads, totalMB);

        int tamanhoPedaco = totalMB / numThreads;
        int sobra = totalMB % numThreads;
        int inicio = 0;
        for (int i = 0; i < numThreads; i++) {
            int fim = inicio + tamanhoPedaco - 1;
            if (i == numThreads - 1) fim += sobra;
            gerenciador.registrarPedaco(i, inicio, fim);
            inicio = fim + 1;
        }

        int passoMB = DownloadThread.PASSO_MB_FIXO;
        double[] fatores = DownloadThread.sortearFatores(numThreads);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        try {
            observador.antesDeIniciar(gerenciador);

            long t0 = System.currentTimeMillis();
            observador.aoIniciar(gerenciador, t0);

            for (int i = 0; i < numThreads; i++) {
                executor.submit(new DownloadThread(i, gerenciador, numThreads, passoMB, fatores[i]));
            }

            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);
            observador.aoTerminar(gerenciador);

            long t1 = System.currentTimeMillis();
            return (t1 - t0) / 1000.0;
        } finally {
            // em caso de cancelamento, interrompe as DownloadThreads que ainda estão dormindo
            executor.shutdownNow();
        }
    }

    static String formatarTempo(double s) {
        if (s < 60) return String.format("%.2f s", s);
        return String.format("%dm %.2fs", (int) (s / 60), s % 60);
    }
}

// Ganchos da simulação para quem quiser acompanhar o download (tela do console, janela Swing...).
interface ObservadorDownload {
    ObservadorDownload NENHUM = new ObservadorDownload() {};

    // pedaços já registrados, cronômetro ainda parado
    default void antesDeIniciar(GerenciadorVisual gerenciador) throws Exception {}

    // cronômetro disparado, threads prestes a ser submetidas
    default void aoIniciar(GerenciadorVisual gerenciador, long t0) throws Exception {}

    // todas as threads terminaram; o tempo gasto aqui ainda entra na medição
    default void aoTerminar(GerenciadorVisual gerenciador) throws Exception {}
}

class EstadoPedaco {
    final int byteInicio, byteFim, tamanhoTotal;
    int progressoAtual = 0;
    boolean concluido = false;

    EstadoPedaco(int byteInicio, int byteFim) {
        this.byteInicio = byteInicio;
        this.byteFim = byteFim;
        this.tamanhoTotal = (byteFim - byteInicio) + 1;
    }

    int getPorcentagem() {
        if (tamanhoTotal == 0) return 100;
        return Math.min(100, (progressoAtual * 100) / tamanhoTotal);
    }
}

class GerenciadorVisual {
    private final EstadoPedaco[] pedacos;
    private final int tamanhoTotalArquivo;
    private boolean primeiroPrint = true;
    private int threadsConcluidas = 0;

    GerenciadorVisual(int numThreads, int tamanhoTotalArquivo) {
        this.pedacos = new EstadoPedaco[numThreads];
        this.tamanhoTotalArquivo = tamanhoTotalArquivo;
    }

    void registrarPedaco(int id, int inicio, int fim) {
        pedacos[id] = new EstadoPedaco(inicio, fim);
    }

    int getTamanhoPedaco(int id) {
        return pedacos[id].tamanhoTotal;
    }

    EstadoPedaco getPedaco(int id) {
        return pedacos[id];
    }

    synchronized void copiarEstado(int[] progresso, int[] porcentagem, boolean[] concluido) {
        for (int i = 0; i < pedacos.length; i++) {
            progresso[i] = pedacos[i].progressoAtual;
            porcentagem[i] = pedacos[i].getPorcentagem();
            concluido[i] = pedacos[i].concluido;
        }
    }

    synchronized boolean todosConcluidos() {
        return threadsConcluidas >= pedacos.length;
    }

    synchronized void atualizar(int id, int avancoMB) {
        EstadoPedaco p = pedacos[id];
        p.progressoAtual += avancoMB;
        if (p.progressoAtual >= p.tamanhoTotal && !p.concluido) {
            p.progressoAtual = p.tamanhoTotal;
            p.concluido = true;
            threadsConcluidas++;
        }
    }

    synchronized void imprimirTela() {
        if (!primeiroPrint) {
            System.out.print("\u001B[" + (pedacos.length + 4) + "A");
        }
        primeiroPrint = false;

        int totalBaixado = 0;

        System.out.println(Main.BOLD + "======================================================================" + Main.RESET);

        for (int i = 0; i < pedacos.length; i++) {
            EstadoPedaco p = pedacos[i];
            totalBaixado += p.progressoAtual;

            String cor = Main.CORES_THREAD[i % Main.CORES_THREAD.length];
            String status = p.concluido
                    ? Main.VERDE + "OK " + Main.RESET
                    : Main.AMARELO + "RUN" + Main.RESET;
            String faixa = String.format("%6d-%6dMB", p.byteInicio, p.byteFim);

            System.out.println(String.format("%sT%02d%s [%s] %s %s %3d%%",
                    cor, i + 1, Main.RESET, faixa, status,
                    barra(p.getPorcentagem(), 24, cor), p.getPorcentagem()));
        }

        System.out.println(Main.BOLD + "----------------------------------------------------------------------" + Main.RESET);

        int porcentagemGeral = (totalBaixado * 100) / tamanhoTotalArquivo;
        System.out.println(String.format("%sTOTAL%s %s %3d%% (%6d/%6d MB)",
                Main.BOLD, Main.RESET, barra(porcentagemGeral, 34, Main.CIANO),
                porcentagemGeral, totalBaixado, tamanhoTotalArquivo));

        System.out.println(Main.BOLD + "======================================================================" + Main.RESET);
    }

    private String barra(int porcentagem, int tamanho, String cor) {
        int blocos = (porcentagem * tamanho) / 100;
        StringBuilder b = new StringBuilder(cor);
        for (int i = 0; i < tamanho; i++) b.append(i < blocos ? "#" : ".");
        b.append(Main.RESET);
        return b.toString();
    }
}

class DownloadThread implements Runnable {
    static final int PASSO_MB_FIXO = 2;

    // Quanto a velocidade média de uma conexão difere das outras (rota, servidor/espelho, latência)
    static final double VARIACAO_ENTRE_CONEXOES = 0.35;
    // Oscilação de um trecho para o outro dentro da mesma conexão (jitter da rede)
    static final double OSCILACAO = 0.25;
    // TCP slow start: a conexão começa a ~20% da velocidade e atinge 100% após esse tempo
    static final long RAMPA_TCP_MS = 1000;
    // Chance, a cada trecho, de perda de pacote -> retransmissão TCP (conexão "trava" por um instante)
    static final double CHANCE_TRAVADA = 0.003;
    static final int TRAVADA_MIN_MS = 300;
    static final int TRAVADA_MAX_MS = 1200;

    private final int id;
    private final GerenciadorVisual gerenciador;
    private final int velocidadeBaseMs;
    private final int passoMB;
    private final double fatorConexao;

    DownloadThread(int id, GerenciadorVisual gerenciador, int totalThreads, int passoMB, double fatorConexao) {
        this.id = id;
        this.gerenciador = gerenciador;
        this.passoMB = passoMB;
        this.fatorConexao = fatorConexao;
        this.velocidadeBaseMs = velocidadeBaseMs(totalThreads);
    }

    static int velocidadeBaseMs(int totalThreads) {
        int penalidade = (totalThreads > 8) ? (totalThreads - 8) * 5 : 0;
        return 15 + penalidade;
    }

    // Estimativa (pelo valor esperado do modelo) de quanto uma rodada com numThreads leva.
    // Serve só para avisar antes de uma varredura longa — o tempo real varia a cada execução.
    static double estimarSegundos(int numThreads, int totalMB) {
        double msPorPasso = velocidadeBaseMs(numThreads) * Math.exp(OSCILACAO * OSCILACAO / 2);

        // o download só termina com a conexão mais lenta: fator esperado da pior de N sorteadas
        double lentidaoPior = numThreads == 1 ? 1.0
                : Math.exp(VARIACAO_ENTRE_CONEXOES * maximoEsperadoNormal(numThreads)
                        + VARIACAO_ENTRE_CONEXOES * VARIACAO_ENTRE_CONEXOES / 2);
        int pedacoComum = totalMB / numThreads;
        int ultimoPedaco = pedacoComum + totalMB % numThreads;
        int passosComum = (pedacoComum + PASSO_MB_FIXO - 1) / PASSO_MB_FIXO;
        int passosUltimo = (ultimoPedaco + PASSO_MB_FIXO - 1) / PASSO_MB_FIXO;
        double ms = Math.max(msConexao(passosComum, msPorPasso * lentidaoPior), msConexao(passosUltimo, msPorPasso));

        ms += passosUltimo * CHANCE_TRAVADA * (TRAVADA_MIN_MS + TRAVADA_MAX_MS) / 2.0;
        return ms / 1000.0;
    }

    // Repete o laço de run() sem dormir: a espera de cada trecho usa a rampa do slow start no INÍCIO
    // do trecho, então com poucos trechos o primeiro (a 20% da velocidade) pesa bastante.
    private static double msConexao(int passos, double msPorPassoPleno) {
        double t = 0;
        for (int i = 0; i < passos; i++) {
            double rampa = Math.min(1.0, 0.2 + 0.8 * t / RAMPA_TCP_MS);
            t += Math.max(1, msPorPassoPleno / rampa);
        }
        return t;
    }

    // Valor esperado do máximo de n normais padrão (aproximação de Blom + inversa da normal de Abramowitz-Stegun)
    private static double maximoEsperadoNormal(int n) {
        double p = (n - 0.375) / (n + 0.25);
        double t = Math.sqrt(-2 * Math.log(1 - p));
        return t - (2.515517 + 0.802853 * t + 0.010328 * t * t)
                / (1 + 1.432788 * t + 0.189269 * t * t + 0.001308 * t * t * t);
    }

    // Sorteia a "qualidade" de cada conexão (distribuição log-normal) e normaliza para média 1.0.
    // Assim a velocidade média é a mesma do modelo antigo — com 1 thread o fator é exatamente 1.0 —
    // e a diferença vem só da desigualdade entre as conexões.
    static double[] sortearFatores(int numThreads) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double[] fatores = new double[numThreads];
        double soma = 0;
        for (int i = 0; i < numThreads; i++) {
            fatores[i] = Math.exp(r.nextGaussian() * VARIACAO_ENTRE_CONEXOES);
            soma += fatores[i];
        }
        double media = soma / numThreads;
        for (int i = 0; i < numThreads; i++) fatores[i] /= media;
        return fatores;
    }

    @Override
    public void run() {
        try {
            int totalMB = gerenciador.getTamanhoPedaco(id);
            int baixado = 0;
            long inicioConexao = System.currentTimeMillis();

            while (baixado < totalMB) {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                long decorrido = System.currentTimeMillis() - inicioConexao;
                double rampa = Math.min(1.0, 0.2 + 0.8 * decorrido / RAMPA_TCP_MS);
                double oscilacao = Math.exp(r.nextGaussian() * OSCILACAO);
                double esperaMs = velocidadeBaseMs / (fatorConexao * rampa) * oscilacao;

                Thread.sleep(Math.max(1, Math.round(esperaMs)));
                if (r.nextDouble() < CHANCE_TRAVADA) {
                    Thread.sleep(r.nextInt(TRAVADA_MIN_MS, TRAVADA_MAX_MS + 1));
                }
                int avanco = Math.min(passoMB, totalMB - baixado);
                baixado += avanco;
                gerenciador.atualizar(id, avanco);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}