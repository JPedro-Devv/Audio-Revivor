package br.jpedro.audiorevivor;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Controladora principal da Interface de Usuário (UI) do aplicativo.
 * <p>
 * Responsável por capturar as interações do usuário (cliques e ajustes de SeekBar),
 * solicitar permissões em tempo de execução (Android 13+) e despachar {@link Intent}s
 * para gerenciar o ciclo de vida do {@link AudioRouterService}.
 * </p>
 *
 * @author João Pedro
 * @version 1.0
 */
public class MainActivity extends AppCompatActivity {

    /** Etiqueta utilizada para filtragem de mensagens no Logcat do IntelliJ IDEA/Android Studio. */
    private static final String TAG = "Main_Activity";

    /** Código de requisição único para identificar o retorno da solicitação de permissão. */
    private static final int PERMISSION_REQUEST_CODE = 100;

    // Componentes de ‘Interface’ Gráfica (XML)
    private TextView tvStatus;
    private Button btnAlternar;
    private TextView tvVolume;
    private SeekBar sbVolume;

    /**
     * Armazena o estado local sobre o serviço estar em execução ou não.
     * Sincronizado dinamicamente no método {@link #onResume()}.
     */
    private boolean isServicoRodando = false;

    /**
     * Método de inicialização do ciclo de vida da Activity.
     * <p>
     * Chamado apenas UMA vez quando a tela é instanciada na memória.
     * Realiza a inflação do layout XML, vinculação dos componentes visuais,
     * configuração de margens para a barra do sistema e registros de escutadores (listeners).
     * </p>

     * @param savedInstanceState Se a Activity for recriada (ex: rotação de tela),
     *                           contém os dados mais recentes salvos. Caso contrário, é null.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
         * Habilita o modo Edge-to-Edge: instrui o sistema operacional a desenhar a ‘interface’
         * ocupando 100% da tela física, passando por baixo das barras de status e navegação.
         * Deve ser chamado ANTES de setContentView.
         */
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        /*
         * Evita que o conteúdo da ‘interface’ fique coberto pela câmera (notch) ou barras do SO.
         * Calcula a altura das barras do sistema (System Bars) e aplica como espaçamento (padding)
         * interno no layout container raiz (R.id.main).
         */
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Verifica e solicita permissões em tempo de execução necessárias para Android 13+
        checarEPedirPermissoes();

        // Mapeia os objetos Java aos elementos declarados no arquivo XML (res/layout/activity_main.xml)
        tvStatus = findViewById(R.id.tvStatus);
        btnAlternar = findViewById(R.id.btnAlternar);
        tvVolume = findViewById(R.id.tvVolume);
        sbVolume = findViewById(R.id.sbVolume);

        // Define o limite máximo do controle de ganho em milibéis (2000 mB = +20 dB)
        sbVolume.setMax(5000);

        // Renderiza o texto formatado com o progresso inicial (ex: 0 mB) logo ao abrir a tela
        tvVolume.setText(getString(R.string.volume_format, sbVolume.getProgress()));

        /*
         * Escutador do clique do botão "Ligar/Parar Áudio".
         * Constrói e envia uma mensagem explícita (Intent) para o AudioRouterService.
         */
        btnAlternar.setOnClickListener(v -> {
            Log.d(TAG, "Botão alternar clicado. Estado atual do serviço: " + isServicoRodando);
            Intent intentService = new Intent(this, AudioRouterService.class);

            if (!isServicoRodando) {
                try {
                    // Anexa a posição atual da SeekBar ao Intent para o serviço iniciar com o ganho correto
                    intentService.putExtra("progress", sbVolume.getProgress());

                    /*
                     * No Android 8.0 (API 26) ou superior, serviços em segundo plano devem ser
                     * iniciados via startForegroundService, prometendo exibir uma notificação fixada.
                     */
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Log.d(TAG, "Iniciando startForegroundService...");
                        startForegroundService(intentService);
                    } else {
                        startService(intentService);
                    }

                    isServicoRodando = true;
                    atualizarInterface();

                } catch (Exception e) {
                    Log.e(TAG, "Erro ao tentar iniciar o serviço: ", e);
                    Toast.makeText(this, "Erro ao iniciar serviço: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }

            } else {
                Log.d(TAG, "Parando o serviço...");

                // Dispara o encerramento do Foreground Service e restaura as rotas padrão
                stopService(intentService);

                isServicoRodando = false;
                atualizarInterface();
            }
        });

        /*
         * Escutador de eventos do controle deslizante de volume (SeekBar).
         */
        sbVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Atualiza o texto do valor em tempo real enquanto o usuário arrasta
                String volumeFormat = getString(R.string.volume_format, progress);
                tvVolume.setText(volumeFormat);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Callback acionado quando o usuário toca na barra (não necessário aqui)
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Otimização: dispara o Intent apenas quando o usuário solta o controle
                int progress = seekBar.getProgress();
                Log.d(TAG, "Volume alterado para: " + progress + " mB");

                if (isServicoRodando) {
                    Intent intentService = new Intent(MainActivity.this, AudioRouterService.class);
                    intentService.putExtra("progress", progress);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intentService);
                    } else {
                        startService(intentService);
                    }
                }
            }
        });
    }

    /**
     * Etapa do Ciclo de Vida executada sempre que a tela ganha o foco do usuário.
     * <p>
     * Roda na abertura do app e sempre que o usuário retorna de outro aplicativo.
     * Utilizada para consultar o estado real do serviço no sistema e manter a UI sincronizada.
     * </p>
     */
    @Override
    protected void onResume() {
        super.onResume();

        // Consulta a ‘flag’ estática no AudioRouterService para saber se o serviço continuou ativo
        isServicoRodando = AudioRouterService.isRunning();

        // Atualiza os componentes visuais com base no estado real
        atualizarInterface();
    }

    /**
     * Sincroniza o texto do status, o rótulo do botão e o progresso da SeekBar
     * com o estado atual da aplicação.
     */
    private void atualizarInterface() {
        if (isServicoRodando) {
            int progress  = AudioRouterService.getUltimoGanho();
            sbVolume.setProgress(progress);

            String volumeFormat = getString(R.string.volume_format, progress);
            tvVolume.setText(volumeFormat);

            tvStatus.setText(R.string.status_running);
            btnAlternar.setText(R.string.btn_stop);
        } else {
            tvStatus.setText(R.string.status_disabled);
            btnAlternar.setText(R.string.btn_start);
        }
    }

    /**
     * Verifica se as permissões necessárias em tempo de execução (Runtime Permissions)
     * foram concedidas pelo usuário e exige a solicitação caso pendente.
     * <p>
     * Requisito obrigatório a partir do Android 13 (API 33 - TIRAMISU) para exibição de
     * notificações vinculadas a Foreground Services (permissão POST_NOTIFICATIONS).
     * </p>
     */
    private void checarEPedirPermissoes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {

                Log.d(TAG, "Solicitando permissão POST_NOTIFICATIONS ao usuário...");
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE
                );
            }
        }
    }

    /**
     * Callback final do ciclo de vida chamado quando a Activity é destruída da memória.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}