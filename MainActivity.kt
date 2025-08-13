package com.example.nawatobiyouapp

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.PlayerState
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    // ▼ Spotify Developer Dashboard の値に置き換え
    private val clientId = "write your clinedt id"
    private val redirectUri = "write your redirect url"
    // ▲

    private var appRemote: SpotifyAppRemote? = null

    private lateinit var etUri: EditText
    private lateinit var btnPlay: Button
    private lateinit var btnPause: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnConnect: Button
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var tvDuration: TextView
    private lateinit var seekBar: SeekBar

    // Spotify 再生状態
    private var lastState: PlayerState? = null
    private var lastEventUptimeMs: Long = 0L

    // UI 更新ループ
    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateProgressUIInterpolated()
            uiHandler.postDelayed(this, 200L) // 5fps程度
        }
    }

    // --- ビープ音関連 ---
    private lateinit var soundPool: SoundPool
    private var beepId: Int = 0
    private var beepLoaded = false
    private var pendingHalfBeep = false  // 未ロード時の保留

    // 半分到達の一度だけ判定
    private var lastTrackUri: String? = null
    private var halfBeepFired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        etUri = findViewById(R.id.etUri)
        btnPlay = findViewById(R.id.btnPlay)
        btnPause = findViewById(R.id.btnPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnConnect = findViewById(R.id.btnConnect)
        tvTitle = findViewById(R.id.tvTitle)
        tvArtist = findViewById(R.id.tvArtist)
        tvElapsed = findViewById(R.id.tvElapsed)
        tvDuration = findViewById(R.id.tvDuration)
        seekBar = findViewById(R.id.seekBar)

        btnConnect.setOnClickListener { connectToSpotify() }
        btnPlay.setOnClickListener {
            val input = etUri.text.toString().trim()
            appRemote?.playerApi?.play(input)
        }
        btnPause.setOnClickListener { appRemote?.playerApi?.pause() }
        btnPrev.setOnClickListener { appRemote?.playerApi?.skipPrevious() }
        btnNext.setOnClickListener { appRemote?.playerApi?.skipNext() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                lastState?.let { state ->
                    val duration = state.track?.duration ?: 0L
                    if (duration > 0) {
                        val target = duration * seekBar.progress / 1000L
                        appRemote?.playerApi?.seekTo(target)
                        // 半分より十分手前に戻したら再武装
                        if (target <= duration * 0.45) {
                            halfBeepFired = false
                            pendingHalfBeep = false
                        }
                    }
                }
            }
        })

        // 効果音の初期化（USAGE_MEDIA で無音リスクを下げる）
        val aa = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(aa)
            .build()

        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && sampleId == beepId) {
                beepLoaded = true
                // 半分通過後にロード完了したケースを救済
                if (pendingHalfBeep && !halfBeepFired) {
                    soundPool.play(beepId, 1f, 1f, 1, 0, 1f)
                    halfBeepFired = true
                }
                pendingHalfBeep = false
            }
        }

        // res/raw/beep.* を読み込み
        beepId = soundPool.load(this, R.raw.beep, 1)
    }

    override fun onStart() {
        super.onStart()
        uiHandler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(ticker)
        appRemote?.let { SpotifyAppRemote.disconnect(it) }
        appRemote = null
        runCatching { soundPool.release() }
    }

    private fun connectToSpotify() {
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(this, params, object : Connector.ConnectionListener {
            override fun onConnected(remote: SpotifyAppRemote) {
                appRemote = remote
                Log.d("MainActivity", "Connected to Spotify")
                subscribePlayerState()
            }

            override fun onFailure(error: Throwable) {
                Log.e("MainActivity", "Connection failed", error)
                Toast.makeText(
                    this@MainActivity,
                    "Spotify接続に失敗: ${error.javaClass.simpleName}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    private fun subscribePlayerState() {
        appRemote?.playerApi?.subscribeToPlayerState()
            ?.setEventCallback { state ->
                // 曲が切り替わったら半分フラグと保留をリセット
                val uriNow = state.track?.uri
                if (uriNow != null && uriNow != lastTrackUri) {
                    lastTrackUri = uriNow
                    halfBeepFired = false
                    pendingHalfBeep = false
                }

                lastState = state
                lastEventUptimeMs = System.nanoTime() / 1_000_000L
                updateStaticUI(state)
                updateProgressUI(state)
            }
    }

    private fun updateStaticUI(state: PlayerState) {
        val track = state.track
        tvTitle.text = track?.name ?: "Title"
        tvArtist.text = track?.artist?.name ?: "Artist"
        val duration = track?.duration ?: 0L
        tvDuration.text = formatMs(duration)
    }

    /** 直近イベントの値での更新（補間なし） */
    private fun updateProgressUI(state: PlayerState) {
        val duration = state.track?.duration ?: 0L
        val pos = state.playbackPosition
        tvElapsed.text = formatMs(pos)
        seekBar.progress = toSeekProgress(pos, duration)
    }

    /** イベント間を playbackSpeed で補間し、なめらか表示＋半分でビープ */
    private fun updateProgressUIInterpolated() {
        val state = lastState ?: return
        val duration = state.track?.duration ?: 0L
        if (duration <= 0) {
            tvElapsed.text = "0:00"
            seekBar.progress = 0
            return
        }
        val nowMs = System.nanoTime() / 1_000_000L
        val dt = nowMs - lastEventUptimeMs
        val speed = if (state.isPaused) 0.0 else (state.playbackSpeed.toDouble().takeIf { it != 0.0 } ?: 1.0)
        var pos = state.playbackPosition + (dt * speed).toLong()
        pos = max(0L, min(pos, duration))

        // 半分に到達（±200msの窓）したら一度だけビープ
        val half = duration / 2
        val window = 200L
        val passedHalf = pos >= (half - window)
        if (!halfBeepFired && passedHalf) {
            if (beepLoaded) {
                soundPool.play(beepId, 1f, 1f, 1, 0, 1f)
                halfBeepFired = true  // 実際に鳴らせた時だけ確定
            } else {
                pendingHalfBeep = true // 後でロード完了時に鳴らす
                Log.d("MainActivity", "beep not loaded yet, pending=true")
            }
        }

        tvElapsed.text = formatMs(pos)
        seekBar.progress = toSeekProgress(pos, duration)
    }

    private fun toSeekProgress(positionMs: Long, durationMs: Long): Int {
        if (durationMs <= 0) return 0
        val ratio = positionMs.toDouble() / durationMs.toDouble()
        return (ratio * 1000.0).toInt().coerceIn(0, 1000)
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
