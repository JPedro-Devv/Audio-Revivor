package br.jpedro.audiorevivor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;

/**
 * Serviço de primeiro plano (Foreground Service) responsável por redirecionar o áudio do sistema
 * para o alto-falante auricular (superior / earpiece) e aplicar ganho digital de volume.
 * <p>
 * O serviço executa de maneira independente da Activity, alterando a rota do {@link AudioManager},
 * mantendo o canal ativo com um {@link AudioTrack} silencioso em loop e aplicando amplificação
 * via {@link LoudnessEnhancer}.
 * </p>

 * @author João Pedro
 * @version 1.0
 */
public class AudioRouterService extends Service {
    /** Variável estática para guardar o último ganho configurado */
    private static int ultimoGanho = 0;

    /** Tag padrão utilizada para filtragem de mensagens no Logcat do IntelliJ IDEA/Android Studio. */
    private static final String TAG = "Audio_Router_Service";

    /** Identificador único do canal de notificação exigido a partir do Android 8.0 (API 26). */
    private static final String CHANNEL_ID = "AudioRouterChannel";

    /**
     * Flag estática que indica se o serviço está em execução no sistema operacional.
     * Permite que instâncias de Activity consultem o estado do serviço sem necessidade de Bind.
     */
    private static boolean isRunning = false;

    /** Gerenciador de hardware de áudio do Android OS. */
    private AudioManager audioManager;

    /** Reproductor de áudio PCM de baixo nível usado para manter a sessão de áudio ativa na HAL. */
    private AudioTrack silentAudioTrack;

    /** Efeito de áudio do sistema utilizado para aumentar o ganho digital acima do limite padrão. */
    private LoudnessEnhancer loudnessEnhancer;

    /**
     * Retorna o estado atual de execução do serviço.
     *
     * @return {@code true} se o serviço estiver ativo no sistema; {@code false} caso contrário.
     */
    public static boolean isRunning() {
        return isRunning;
    }

    /**
     * Chamado pelo sistema operacional quando a instância do serviço é criada na memória.
     * Marca a flag estática {@link #isRunning} como verdadeira e inicializa o {@link AudioManager}.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        Log.d(TAG, "Service onCreate chamado.");

        // Obtém a referência ao serviço de áudio do sistema Android
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * Ponto de entrada de comandos executado sempre que {@code startService()} ou
     * {@code startForegroundService()} é chamado pela MainActivity.
     *
     * @param intent  Objeto Intent contendo ações ou parâmetros extras (ex: valor de ganho da SeekBar).
     * @param flags   Atributos adicionais da requisição de início.
     * @param startId Identificador numérico único desta execução.
     * @return {@link #START_STICKY} para instruir o Android a recriar o serviço caso seja terminado por falta de memória.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand chamado.");

        try {
            // Processa o valor de ganho do volume se enviado no Intent
            if (intent != null && intent.hasExtra("progress")) {
                int ganhoRecebido = intent.getIntExtra("progress", 0);
                aplicarAmplificacaoDeVolume(ganhoRecebido);
            }

            // Exibe a notificação obrigatória de serviço em primeiro plano
            criarNotificacaoEIniciarForeground();

            // Configura o modo de áudio para direcionar a saída ao alto-falante superior
            ativarAltoFalanteSuperior();

            // Inicia a reprodução silenciosa para prevenir o timeout do driver de áudio
            iniciarTrilhaSilenciosa();

        } catch (Exception e) {
            Log.e(TAG, "Erro dentro do onStartCommand: ", e);
        }

        return START_STICKY;
    }

    /**
     * Configura o efeito {@link LoudnessEnhancer} para aplicar ganho digital ao sinal de áudio.
     *
     * @param ganhoEmMB Valor do ganho desejado em milibéis (mB). Exemplo: 1000 mB = +10 dB.
     */
    private void aplicarAmplificacaoDeVolume(int ganhoEmMB) {
        try {
            /*
             * A AudioSession 0 vincula o efeito à saída de áudio global do sistema operacional
             * (Global Output Mix), afetando a reprodução de mídias de qualquer aplicativo.
             */
            if (loudnessEnhancer == null) {
                loudnessEnhancer = new LoudnessEnhancer(0);
            }

            loudnessEnhancer.setTargetGain(ganhoEmMB);
            loudnessEnhancer.setEnabled(true);

            ultimoGanho = ganhoEmMB;

            Log.d(TAG, "LoudnessEnhancer ativado com ganho de: " + ganhoEmMB + " mB");
        } catch (Exception e) {
            Log.e(TAG, "Erro ao inicializar LoudnessEnhancer: ", e);
        }
    }

    /** Getter estático para a MainActivity consultar no onResume() */
    public static int getUltimoGanho() {
        return ultimoGanho;
    }

    /**
     * Desativa o efeito de amplificação e libera a instância do {@link LoudnessEnhancer}.
     */
    private void removerAmplificacaoDeVolume() {
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.setEnabled(false);
                loudnessEnhancer.release();
                Log.d(TAG, "LoudnessEnhancer desativado.");
            } catch (Exception ignored) {}
            loudnessEnhancer = null;
        }
    }

    /**
     * Modifica o perfil do {@link AudioManager} para {@code MODE_IN_COMMUNICATION}
     * e seleciona o dispositivo de áudio auricular (earpiece).
     */
    private void ativarAltoFalanteSuperior() {
        if (audioManager == null) {
            Log.d(TAG, "AudioManager é nulo!");
            return;
        }

        Log.d(TAG, "Alterando mode para MODE_IN_COMMUNICATION...");
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+ (API 31)
            List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
            boolean encontrouEarpiece = false;

            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    boolean sucesso = audioManager.setCommunicationDevice(device);
                    Log.d(TAG, "Dispositivo Earpiece encontrado! Sucesso ao definir: " + sucesso);
                    encontrouEarpiece = true;
                    break;
                }
            }
            if (!encontrouEarpiece) {
                Log.w(TAG, "Nenhum TYPE_BUILTIN_EARPIECE foi localizado na lista de dispositivos!");
            }

        } else {
            // Suporte para versões legadas do Android (anterior ao API 31)
            audioManager.setSpeakerphoneOn(false);
        }
    }

    /**
     * Instancia um {@link AudioTrack} em modo estático reproduzindo um buffer zerado (silêncio) em loop.
     * <p>
     * <b>Finalidade Técnica:</b> Em certas distribuições Android (como ColorOS/Oplus em processadores MediaTek),
     * a camada HAL desativa o canal de áudio após 3 segundos se nenhum fluxo for detectado.
     * Este loop impede que a HAL feche a conexão.
     * </p>
     */
    private void iniciarTrilhaSilenciosa() {
        if (silentAudioTrack != null) return;

        try {
            int sampleRate = 44100;
            int bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();

            // Buffer com sinal PCM zerado (silêncio absoluto)
            byte[] silentBuffer = new byte[bufferSize];

            silentAudioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();

            silentAudioTrack.write(silentBuffer, 0, silentBuffer.length);
            // Configura o loop infinito (-1) cobrindo todo o buffer estático
            silentAudioTrack.setLoopPoints(0, silentBuffer.length / 2, -1);
            silentAudioTrack.play();

            Log.d(TAG, "Trilha silenciosa iniciada para manter o canal ativo.");
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar AudioTrack silencioso: ", e);
        }
    }

    /**
     * Interrompe a reprodução e libera a memória alocada pelo {@link AudioTrack}.
     */
    private void pararTrilhaSilenciosa() {
        if (silentAudioTrack != null) {
            try {
                silentAudioTrack.stop();
                silentAudioTrack.release();
                Log.d(TAG, "Trilha silenciosa liberada.");
            } catch (Exception ignored) {}
            silentAudioTrack = null;
        }
    }

    /**
     * Restaura as rotas de áudio do sistema operacional ao estado normal,
     * desativando amplificadores e o loop silencioso.
     */
    private void desativarRedirecionamento() {
        Log.d(TAG, "Desativando redirecionamento de áudio...");

        removerAmplificacaoDeVolume();
        pararTrilhaSilenciosa();

        if (audioManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice();
        } else {
            audioManager.setSpeakerphoneOn(false);
        }

        // Retorna o gerenciador de áudio ao modo padrão do dispositivo
        audioManager.setMode(AudioManager.MODE_NORMAL);
    }

    /**
     * Cria o canal de notificação obrigatório (API 26+) e registra o serviço como
     * Foreground Service via {@link #startForeground(int, Notification)}.
     */
    private void criarNotificacaoEIniciarForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Roteador de Áudio",
                    NotificationManager.IMPORTANCE_LOW
            );

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_desc))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();

        // No Android 14+ (API 34), exige a especificação do foregroundServiceType no método
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
        } else {
            startForeground(1, notification);
        }

        Log.d(TAG, "startForeground executado com sucesso.");
    }

    /**
     * Executado quando o serviço é finalizado pelo aplicativo ou pelo sistema.
     * Restaura a rota normal de áudio e atualiza a flag estática de execução.
     */
    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy chamado.");
        desativarRedirecionamento();
        isRunning = false;
        super.onDestroy();
    }

    /**
     * Método do ciclo de vida para serviços vinculados (Bound Services).
     * Retorna {@code null} pois o serviço utiliza o modelo Started Service via Intent.
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}