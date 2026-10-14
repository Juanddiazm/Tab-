package com.juanddiazm.tabu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Vibrator;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity {

    // Palette ("noche de juegos"): ink ground, cream cards, coral / sunflower / mint accents.
    static final int INK = 0xFF17122E, SURFACE = 0xFF241D45, SURFACE2 = 0xFF2F2759, CREAM = 0xFFFFF6E6,
            MUTED = 0xFFA9A1CF, CARD_MUTED = 0xFF6B6288, CARD_LINE = 0xFFEFE6D6, CARD_DASH = 0xFFE3D8C4,
            CORAL = 0xFFFF5A4E, CORAL_TEXT = 0xFFFF7A70, CORAL_DARK = 0xFFD93A2F,
            YELLOW = 0xFFFFC93C, MINT = 0xFF34D399;
    static final int[] TEAM_COLORS = {0xFF5B8CFF, 0xFFFF9F1C, 0xFF2DD4BF, 0xFFC084FC};
    static final int[] TIME_OPTIONS = {30, 45, 60, 90, 120};
    static final String[] TIME_LABELS = {"30s", "45s", "60s", "90s", "2m"};
    static final int[] SCORE_OPTIONS = {10, 20, 30, 50};
    static final int RES_CORRECT = 1, RES_TABOO = 2, RES_SKIP = 3;
    static final int SCR_SETUP = 0, SCR_READY = 1, SCR_PLAY = 2, SCR_SUMMARY = 3, SCR_END = 4, SCR_COUNTDOWN = 5;
    static final int W_REGULAR = 0, W_SEMI = 1, W_BOLD = 2;
    static final Locale ES = new Locale("es");

    // Settings
    int numTeams = 2;
    final String[] teamNames = new String[4];
    int timeIdx = 2, scoreIdx = 1;
    boolean skipPenalty = false, soundOn = true;

    // Game state
    final int[] scores = new int[4];
    int currentTeam, turnsPlayed;
    int[] deck;
    int deckPos;
    long deckSeed;
    int currentCard = -1;
    final ArrayList<int[]> turnLog = new ArrayList<int[]>();
    long remainingMs, lastTickAt, lastMarkAt;
    boolean running, paused;
    int lastBeepSecond, countdown;
    int screen = SCR_SETUP;

    final Handler handler = new Handler();
    ToneGenerator tone;
    Vibrator vibrator;
    SharedPreferences prefs;
    Typeface[] fonts = new Typeface[3];
    final ArrayList<EditText> nameFields = new ArrayList<EditText>();

    // Play screen views
    TextView timerText, statsText, wordText, undoText, countdownText;
    TextView[] forbiddenViews;
    View cardView, pauseView;
    TimeBar timeBar;

    final Runnable tick = new Runnable() {
        public void run() {
            onTick();
        }
    };

    final Runnable countdownStep = new Runnable() {
        public void run() {
            onCountdownStep();
        }
    };

    interface IntCallback {
        void call(int i);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        prefs = getSharedPreferences("tabu", MODE_PRIVATE);
        loadFonts();
        loadSettings();
        loadDeck();
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
        } catch (RuntimeException e) {
            tone = null;
        }
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        showSetup();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (screen == SCR_PLAY && running && !paused) pauseTurn();
        if (screen == SCR_COUNTDOWN) showReady();
        saveDeck();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(countdownStep);
        if (tone != null) tone.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        switch (screen) {
            case SCR_SETUP:
                super.onBackPressed();
                break;
            case SCR_COUNTDOWN:
                showReady();
                break;
            case SCR_PLAY:
                if (!paused) pauseTurn();
                break;
            case SCR_SUMMARY:
                Toast.makeText(this, "Toca CONTINUAR para seguir", Toast.LENGTH_SHORT).show();
                break;
            case SCR_END:
                showSetup();
                break;
            default:
                confirmQuit();
        }
    }

    void loadFonts() {
        String[] files = {"fonts/bricolage-regular.ttf", "fonts/bricolage-semibold.ttf", "fonts/bricolage-extrabold.ttf"};
        for (int i = 0; i < 3; i++) {
            try {
                fonts[i] = Typeface.createFromAsset(getAssets(), files[i]);
            } catch (RuntimeException e) {
                fonts[i] = i == W_REGULAR ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD;
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    void loadSettings() {
        numTeams = clamp(prefs.getInt("numTeams", 2), 2, 4);
        for (int i = 0; i < 4; i++) teamNames[i] = prefs.getString("team" + i, "Equipo " + (i + 1));
        timeIdx = clamp(prefs.getInt("timeIdx", 2), 0, TIME_OPTIONS.length - 1);
        scoreIdx = clamp(prefs.getInt("scoreIdx", 1), 0, SCORE_OPTIONS.length - 1);
        skipPenalty = prefs.getBoolean("skipPenalty", false);
        soundOn = prefs.getBoolean("soundOn", true);
    }

    void saveSettings() {
        SharedPreferences.Editor e = prefs.edit();
        e.putInt("numTeams", numTeams);
        for (int i = 0; i < 4; i++) e.putString("team" + i, teamNames[i]);
        e.putInt("timeIdx", timeIdx);
        e.putInt("scoreIdx", scoreIdx);
        e.putBoolean("skipPenalty", skipPenalty);
        e.putBoolean("soundOn", soundOn);
        e.apply();
    }

    /** The deck is a seeded shuffle; seed + position persist so cards don't repeat between games. */
    void loadDeck() {
        deckSeed = prefs.getLong("deckSeed", 0);
        deckPos = prefs.getInt("deckPos", 0);
        if (deckSeed == 0 || prefs.getInt("deckSize", 0) != Words.CARDS.length) {
            deckSeed = new Random().nextLong() | 1;
            deckPos = 0;
        }
        deck = shuffled(deckSeed);
    }

    void saveDeck() {
        prefs.edit().putLong("deckSeed", deckSeed).putInt("deckPos", deckPos)
                .putInt("deckSize", Words.CARDS.length).apply();
    }

    static int[] shuffled(long seed) {
        int n = Words.CARDS.length;
        int[] d = new int[n];
        for (int i = 0; i < n; i++) d[i] = i;
        Random r = new Random(seed);
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = d[i];
            d[i] = d[j];
            d[j] = t;
        }
        return d;
    }

    int drawCard() {
        if (deckPos >= deck.length) {
            deckSeed = new Random().nextLong() | 1;
            deck = shuffled(deckSeed);
            deckPos = 0;
        }
        return deck[deckPos++];
    }

    // ------------------------------------------------------------------ setup screen

    void showSetup() {
        screen = SCR_SETUP;
        running = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(countdownStep);

        LinearLayout col = column();
        col.setPadding(dp(20), dp(28), dp(20), dp(16));

        // Header: logo + title + help
        LinearLayout head = row();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        head.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text("Tabú", 40, CREAM, W_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = dp(12);
        head.addView(title, tlp);
        TextView help = circleButton("?", SURFACE, "Cómo se juega");
        help.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showRules();
            }
        });
        head.addView(help, new LinearLayout.LayoutParams(dp(44), dp(44)));
        col.addView(head);

        TextView sub = text("Describe la palabra sin decir las prohibidas. Gana el equipo que llegue primero a la meta.", 15, MUTED, W_REGULAR);
        sub.setLineSpacing(0, 1.15f);
        col.addView(sub, matchWrap(0, dp(14), 0, dp(18)));

        // Teams card
        LinearLayout teams = card();
        LinearLayout teamHeader = row();
        teamHeader.addView(label("EQUIPOS"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView minus = smallButton("−", "Quitar equipo");
        TextView count = text(String.valueOf(numTeams), 18, CREAM, W_BOLD);
        count.setGravity(Gravity.CENTER);
        TextView plus = smallButton("+", "Agregar equipo");
        teamHeader.addView(minus, new LinearLayout.LayoutParams(dp(44), dp(40)));
        teamHeader.addView(count, new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));
        teamHeader.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(40)));
        minus.setEnabled(numTeams > 2);
        minus.setAlpha(numTeams > 2 ? 1f : 0.4f);
        plus.setEnabled(numTeams < 4);
        plus.setAlpha(numTeams < 4 ? 1f : 0.4f);
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (numTeams > 2) {
                    readNames();
                    numTeams--;
                    showSetup();
                }
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (numTeams < 4) {
                    readNames();
                    numTeams++;
                    showSetup();
                }
            }
        });
        teams.addView(teamHeader, matchWrap(0, 0, 0, dp(10)));

        nameFields.clear();
        for (int i = 0; i < numTeams; i++) {
            LinearLayout field = row();
            field.setPadding(dp(14), 0, dp(14), 0);
            field.setBackgroundDrawable(round(SURFACE2, 14));
            field.addView(dot(TEAM_COLORS[i], 14), new LinearLayout.LayoutParams(dp(14), dp(14)));
            EditText et = new EditText(this);
            et.setText(teamNames[i]);
            et.setSingleLine(true);
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(18)});
            et.setTextColor(CREAM);
            et.setHintTextColor(MUTED);
            et.setHint("Equipo " + (i + 1));
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            et.setTypeface(fonts[W_SEMI]);
            et.setBackgroundDrawable(null);
            et.setPadding(dp(12), 0, 0, 0);
            et.setContentDescription("Nombre del equipo " + (i + 1));
            field.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            nameFields.add(et);
            teams.addView(field, matchHeight(dp(52), 0, 0, 0, i < numTeams - 1 ? dp(8) : 0));
        }
        col.addView(teams, matchWrap(0, 0, 0, dp(14)));

        // Rules card
        LinearLayout rules = card();
        rules.addView(label("TIEMPO POR TURNO"), matchWrap(0, 0, 0, dp(10)));
        rules.addView(segmented(TIME_LABELS, timeIdx, new IntCallback() {
            public void call(int i) {
                timeIdx = i;
            }
        }), matchWrap(0, 0, 0, dp(18)));
        rules.addView(label("PUNTOS PARA GANAR"), matchWrap(0, 0, 0, dp(10)));
        String[] scoreLabels = new String[SCORE_OPTIONS.length];
        for (int i = 0; i < SCORE_OPTIONS.length; i++) scoreLabels[i] = String.valueOf(SCORE_OPTIONS[i]);
        rules.addView(segmented(scoreLabels, scoreIdx, new IntCallback() {
            public void call(int i) {
                scoreIdx = i;
            }
        }), matchWrap(0, 0, 0, dp(10)));
        rules.addView(switchRow("Pasar resta 1 punto", skipPenalty, new IntCallback() {
            public void call(int on) {
                skipPenalty = on == 1;
            }
        }));
        rules.addView(switchRow("Sonido y vibración", soundOn, new IntCallback() {
            public void call(int on) {
                soundOn = on == 1;
            }
        }));
        col.addView(rules, matchWrap(0, 0, 0, 0));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col);

        // Pinned call to action
        LinearLayout bottom = column();
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(dp(20), dp(12), dp(20), dp(16));
        TextView play = bigButton("¡Jugar!", YELLOW, 24);
        play.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                readNames();
                saveSettings();
                newGame();
            }
        });
        bottom.addView(play, matchHeight(dp(68), 0, 0, 0, dp(8)));
        bottom.addView(text(Words.CARDS.length + " tarjetas · sin repetir", 13, MUTED, W_REGULAR));

        LinearLayout root = column();
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(bottom, matchWrap(0, 0, 0, 0));
        setContentView(root);
    }

    void readNames() {
        for (int i = 0; i < nameFields.size() && i < 4; i++) {
            String s = nameFields.get(i).getText().toString().trim();
            teamNames[i] = s.length() == 0 ? "Equipo " + (i + 1) : s;
        }
    }

    void showRules() {
        new AlertDialog.Builder(this)
                .setTitle("Cómo se juega")
                .setMessage("• Formen de 2 a 4 equipos y siéntense intercalados.\n\n"
                        + "• En cada turno, una persona describe la palabra grande para que su equipo "
                        + "la adivine, SIN decir ninguna de las palabras prohibidas (ni la palabra misma, "
                        + "ni partes de ellas).\n\n"
                        + "• No vale hacer gestos o sonidos, deletrear ni decir \"suena como…\".\n\n"
                        + "• Alguien de otro equipo mira la pantalla: si se dice una palabra prohibida, "
                        + "presiona TABÚ (−1).\n\n"
                        + "• ¡CORRECTO! suma 1. PASAR salta la palabra (puede restar 1 si lo activas). "
                        + "Si alguien toca un botón por error, usa Deshacer.\n\n"
                        + "• Al acabar el tiempo, el celular pasa al siguiente equipo con otra persona describiendo.\n\n"
                        + "• Gana el primer equipo en llegar a la meta al cerrar una ronda completa.")
                .setPositiveButton("¡Entendido!", null)
                .show();
    }

    // ------------------------------------------------------------------ game flow

    void newGame() {
        for (int i = 0; i < 4; i++) scores[i] = 0;
        currentTeam = 0;
        turnsPlayed = 0;
        showReady();
    }

    int turnSeconds() {
        return TIME_OPTIONS[timeIdx];
    }

    int targetScore() {
        return SCORE_OPTIONS[scoreIdx];
    }

    void showReady() {
        screen = SCR_READY;
        running = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(countdownStep);
        int teamColor = TEAM_COLORS[currentTeam];

        LinearLayout col = column();

        // Team-colored handoff panel
        LinearLayout panel = column();
        panel.setPadding(dp(24), dp(28), dp(24), dp(32));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(teamColor);
        float r = dp(36);
        pbg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        panel.setBackgroundDrawable(pbg);
        LinearLayout meta = row();
        meta.addView(text("RONDA " + (turnsPlayed / numTeams + 1), 13, INK, W_BOLD), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        meta.addView(text("META " + targetScore(), 13, INK, W_BOLD));
        panel.addView(meta);
        panel.addView(text("Pasa el celular a", 18, INK, W_SEMI), matchWrap(0, dp(32), 0, 0));
        TextView name = text(teamNames[currentTeam], 48, INK, W_BOLD);
        name.setLineSpacing(0, 0.9f);
        panel.addView(name, matchWrap(0, 0, 0, dp(12)));
        TextView hint = text("Una persona describe. Su equipo adivina. Alguien de otro equipo vigila la pantalla.", 16, INK, W_REGULAR);
        hint.setLineSpacing(0, 1.15f);
        panel.addView(hint);
        col.addView(panel, matchWrap(0, 0, 0, 0));

        // Scoreboard with progress toward the goal
        LinearLayout board = column();
        board.setPadding(dp(20), dp(24), dp(20), 0);
        board.addView(label("MARCADOR"), matchWrap(0, 0, 0, dp(12)));
        for (int i = 0; i < numTeams; i++) {
            boolean me = i == currentTeam;
            LinearLayout line = row();
            line.addView(text(teamNames[i], 17, me ? CREAM : MUTED, me ? W_BOLD : W_SEMI), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(text(String.valueOf(scores[i]), 17, me ? CREAM : MUTED, W_BOLD));
            board.addView(line, matchWrap(0, 0, 0, dp(6)));
            TimeBar bar = new TimeBar(this, SURFACE2);
            bar.set(scores[i] / (float) targetScore(), TEAM_COLORS[i]);
            board.addView(bar, matchHeight(dp(10), 0, 0, 0, dp(14)));
        }

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout top = column();
        top.addView(col);
        top.addView(board);
        sv.addView(top);

        LinearLayout bottom = column();
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(dp(20), dp(12), dp(20), dp(8));
        TextView start = bigButton("Estoy listo", YELLOW, 26);
        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startCountdown();
            }
        });
        bottom.addView(start, matchHeight(dp(76), 0, 0, 0, dp(8)));
        bottom.addView(text("Cuenta 3, 2, 1 y arranca el reloj", 13, MUTED, W_REGULAR));
        TextView quit = text("Salir al menú", 15, MUTED, W_SEMI);
        quit.setGravity(Gravity.CENTER);
        quit.setClickable(true);
        quit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmQuit();
            }
        });
        bottom.addView(quit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));

        LinearLayout root = column();
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(bottom, matchWrap(0, 0, 0, 0));
        setContentView(root);
    }

    void confirmQuit() {
        new AlertDialog.Builder(this)
                .setTitle("¿Salir de la partida?")
                .setMessage("Se perderá el marcador actual.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salir", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        showSetup();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ 3-2-1 countdown

    void startCountdown() {
        screen = SCR_COUNTDOWN;
        countdown = 3;
        LinearLayout root = column();
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(TEAM_COLORS[currentTeam]);
        root.addView(text("¡Prepárate!", 22, INK, W_BOLD));
        countdownText = text("3", 180, INK, W_BOLD);
        countdownText.setGravity(Gravity.CENTER);
        root.addView(countdownText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(text(teamNames[currentTeam], 20, INK, W_SEMI));
        setContentView(root);
        beep(ToneGenerator.TONE_PROP_BEEP, 120);
        handler.postDelayed(countdownStep, 1000);
    }

    void onCountdownStep() {
        if (screen != SCR_COUNTDOWN) return;
        countdown--;
        if (countdown <= 0) {
            beep(ToneGenerator.TONE_PROP_BEEP2, 200);
            startTurn();
            return;
        }
        countdownText.setText(String.valueOf(countdown));
        countdownText.setScaleX(1.3f);
        countdownText.setScaleY(1.3f);
        countdownText.animate().scaleX(1f).scaleY(1f).setDuration(250).start();
        beep(ToneGenerator.TONE_PROP_BEEP, 120);
        handler.postDelayed(countdownStep, 1000);
    }

    // ------------------------------------------------------------------ play screen

    void startTurn() {
        turnLog.clear();
        remainingMs = turnSeconds() * 1000L;
        running = true;
        paused = false;
        lastBeepSecond = -1;
        lastMarkAt = 0;
        showPlay();
        nextWord(false);
        lastTickAt = SystemClock.elapsedRealtime();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void showPlay() {
        screen = SCR_PLAY;
        int teamColor = TEAM_COLORS[currentTeam];

        LinearLayout col = column();
        col.setPadding(dp(16), dp(16), dp(16), dp(8));

        // Top bar: team pill, running tally, pause
        LinearLayout top = row();
        TextView team = text(teamNames[currentTeam], 15, INK, W_BOLD);
        team.setSingleLine(true);
        team.setGravity(Gravity.CENTER_VERTICAL);
        team.setPadding(dp(14), 0, dp(14), 0);
        team.setBackgroundDrawable(round(teamColor, 18));
        top.addView(team, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        statsText = text("", 15, CREAM, W_BOLD);
        statsText.setGravity(Gravity.CENTER_VERTICAL);
        statsText.setPadding(dp(12), 0, dp(12), 0);
        statsText.setBackgroundDrawable(round(SURFACE, 18));
        top.addView(statsText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        ImageView pause = new ImageView(this);
        pause.setImageResource(R.drawable.ic_pause);
        pause.setScaleType(ImageView.ScaleType.CENTER);
        pause.setBackgroundDrawable(pressable(SURFACE, 22));
        pause.setClickable(true);
        pause.setContentDescription("Pausar");
        pause.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pauseTurn();
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(44), dp(44));
        plp.leftMargin = dp(10);
        top.addView(pause, plp);
        col.addView(top);

        // Timer
        LinearLayout timer = row();
        timerText = text("", 44, CREAM, W_BOLD);
        timer.addView(timerText, new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT));
        timeBar = new TimeBar(this, SURFACE2);
        timer.addView(timeBar, new LinearLayout.LayoutParams(0, dp(12), 1));
        col.addView(timer, matchWrap(0, dp(6), 0, dp(10)));

        // Card (cream, with a team-colored "lift")
        FrameLayout frame = new FrameLayout(this);
        LinearLayout card = column();
        LayerDrawable cardBg = new LayerDrawable(new Drawable[]{round(teamColor, 28), round(CREAM, 28)});
        cardBg.setLayerInset(1, 0, 0, 0, dp(8));
        card.setBackgroundDrawable(cardBg);
        card.setPadding(dp(22), dp(22), dp(22), dp(8 + 12));
        TextView describe = text("DESCRIBE", 13, CARD_MUTED, W_BOLD);
        describe.setGravity(Gravity.CENTER);
        card.addView(describe, matchWrap(0, 0, 0, 0));
        wordText = text("", 46, INK, W_BOLD);
        wordText.setGravity(Gravity.CENTER);
        wordText.setLineSpacing(0, 0.95f);
        card.addView(wordText, matchWrap(0, 0, 0, dp(16)));
        View dash = new View(this);
        GradientDrawable dashBg = new GradientDrawable();
        dashBg.setShape(GradientDrawable.LINE);
        dashBg.setStroke(dp(2), CARD_DASH, dp(8), dp(6));
        dash.setBackgroundDrawable(dashBg);
        dash.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        card.addView(dash, matchHeight(dp(4), 0, 0, 0, dp(12)));
        TextView noSay = text("NO PUEDES DECIR", 13, CORAL_DARK, W_BOLD);
        noSay.setGravity(Gravity.CENTER);
        card.addView(noSay, matchWrap(0, 0, 0, dp(2)));
        forbiddenViews = new TextView[5];
        for (int i = 0; i < 5; i++) {
            TextView f = text("", 26, INK, W_SEMI);
            f.setGravity(Gravity.CENTER);
            forbiddenViews[i] = f;
            card.addView(f, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            if (i < 4) {
                View sep = new View(this);
                sep.setBackgroundColor(CARD_LINE);
                card.addView(sep, matchHeight(dp(1), 0, 0, 0, 0));
            }
        }
        cardView = card;
        frame.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pauseView = buildPauseOverlay();
        pauseView.setVisibility(View.GONE);
        frame.addView(pauseView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Actions: small Tabú / Pasar, big Correcto under the thumb
        LinearLayout pair = row();
        pair.setBaselineAligned(false);
        TextView taboo = bigButton("Tabú −1", CORAL, 20);
        TextView skip = bigButton(skipPenalty ? "Pasar −1" : "Pasar", SURFACE2, 20);
        skip.setTextColor(CREAM);
        taboo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mark(RES_TABOO);
            }
        });
        skip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mark(RES_SKIP);
            }
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(64), 1);
        l1.rightMargin = dp(10);
        pair.addView(taboo, l1);
        pair.addView(skip, new LinearLayout.LayoutParams(0, dp(64), 1));
        col.addView(pair, matchWrap(0, dp(16), 0, dp(10)));
        TextView ok = bigButton("¡Correcto! +1", MINT, 26);
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mark(RES_CORRECT);
            }
        });
        col.addView(ok, matchHeight(dp(80), 0, 0, 0, 0));
        undoText = text("", 14, MUTED, W_SEMI);
        undoText.setGravity(Gravity.CENTER);
        undoText.setSingleLine(true);
        undoText.setClickable(true);
        undoText.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                undo();
            }
        });
        col.addView(undoText, matchHeight(dp(40), 0, dp(2), 0, 0));

        setContentView(col);
        updateTimerViews();
        updateStats();
    }

    View buildPauseOverlay() {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        box.setBackgroundDrawable(round(SURFACE, 28));
        box.setClickable(true);
        TextView t = text("Pausa", 44, CREAM, W_BOLD);
        t.setGravity(Gravity.CENTER);
        box.addView(t, matchWrap(0, 0, 0, dp(4)));
        TextView s = text("La tarjeta está oculta", 15, MUTED, W_REGULAR);
        s.setGravity(Gravity.CENTER);
        box.addView(s, matchWrap(0, 0, 0, dp(24)));
        TextView resume = bigButton("Continuar", YELLOW, 20);
        resume.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                resumeTurn();
            }
        });
        box.addView(resume, matchHeight(dp(60), 0, 0, 0, dp(12)));
        TextView end = bigButton("Terminar turno", SURFACE2, 17);
        end.setTextColor(CREAM);
        end.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                endTurn(false);
            }
        });
        box.addView(end, matchHeight(dp(54), 0, 0, 0, dp(12)));
        TextView quit = bigButton("Salir al menú", SURFACE2, 17);
        quit.setTextColor(CORAL_TEXT);
        quit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmQuit();
            }
        });
        box.addView(quit, matchHeight(dp(54), 0, 0, 0, 0));
        return box;
    }

    void nextWord(boolean animate) {
        currentCard = drawCard();
        showCard(currentCard, animate);
    }

    void showCard(int card, boolean animate) {
        String[] c = Words.card(card);
        String w = c[0].substring(0, 1) + c[0].substring(1).toLowerCase(ES);
        wordText.setText(w);
        wordText.setTextSize(TypedValue.COMPLEX_UNIT_SP, w.length() > 14 ? 32 : w.length() > 10 ? 38 : 46);
        for (int i = 0; i < 5; i++) forbiddenViews[i].setText(i + 1 < c.length ? c[i + 1] : "");
        if (animate) {
            cardView.setTranslationX(dp(36));
            cardView.setAlpha(0f);
            cardView.animate().translationX(0).alpha(1f).setDuration(160).start();
        }
    }

    void mark(int result) {
        if (!running || paused) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastMarkAt < 300) return; // ignore accidental double taps
        lastMarkAt = now;
        turnLog.add(new int[]{currentCard, result});
        if (result == RES_CORRECT) {
            beep(ToneGenerator.TONE_PROP_ACK, 150);
            vibrate(40);
        } else if (result == RES_TABOO) {
            beep(ToneGenerator.TONE_SUP_ERROR, 450);
            vibrate(300);
        } else {
            beep(ToneGenerator.TONE_PROP_NACK, 150);
        }
        updateStats();
        nextWord(true);
    }

    /** Takes back the last tap and shows that card again. */
    void undo() {
        if (!running || paused || turnLog.isEmpty()) return;
        int[] last = turnLog.remove(turnLog.size() - 1);
        if (deckPos > 0 && deck[deckPos - 1] == currentCard) deckPos--; // current card goes back on the deck
        currentCard = last[0];
        showCard(currentCard, false);
        updateStats();
    }

    void onTick() {
        if (!running || paused) return;
        long now = SystemClock.elapsedRealtime();
        remainingMs -= now - lastTickAt;
        lastTickAt = now;
        if (remainingMs <= 0) {
            remainingMs = 0;
            endTurn(true);
            return;
        }
        int secs = (int) ((remainingMs + 999) / 1000);
        if (secs <= 5 && secs != lastBeepSecond) {
            lastBeepSecond = secs;
            beep(ToneGenerator.TONE_PROP_BEEP, 120);
        }
        updateTimerViews();
        handler.postDelayed(tick, 100);
    }

    void updateTimerViews() {
        if (timerText == null) return;
        int secs = (int) ((remainingMs + 999) / 1000);
        timerText.setText(String.format(Locale.US, "%d:%02d", secs / 60, secs % 60));
        timerText.setTextColor(secs <= 10 ? CORAL_TEXT : CREAM);
        float frac = remainingMs / (turnSeconds() * 1000f);
        timeBar.set(frac, frac > 0.2f ? YELLOW : CORAL);
    }

    void updateStats() {
        int correct = 0, taboo = 0;
        for (int[] e : turnLog) {
            if (e[1] == RES_CORRECT) correct++;
            else if (e[1] == RES_TABOO) taboo++;
        }
        statsText.setText("✓ " + correct + "   ✗ " + taboo);
        if (turnLog.isEmpty()) {
            undoText.setText("");
            undoText.setEnabled(false);
        } else {
            undoText.setText("↶ Deshacer: " + Words.card(turnLog.get(turnLog.size() - 1)[0])[0].toLowerCase(ES));
            undoText.setEnabled(true);
        }
    }

    int pointsFor(int result) {
        if (result == RES_CORRECT) return 1;
        if (result == RES_TABOO) return -1;
        return skipPenalty ? -1 : 0;
    }

    int turnPoints() {
        int p = 0;
        for (int[] e : turnLog) p += pointsFor(e[1]);
        return p;
    }

    void pauseTurn() {
        if (!running || paused) return;
        paused = true;
        handler.removeCallbacks(tick);
        long now = SystemClock.elapsedRealtime();
        remainingMs -= now - lastTickAt;
        if (remainingMs < 0) remainingMs = 0;
        updateTimerViews();
        cardView.setVisibility(View.INVISIBLE);
        pauseView.setVisibility(View.VISIBLE);
    }

    void resumeTurn() {
        if (!running || !paused) return;
        paused = false;
        cardView.setVisibility(View.VISIBLE);
        pauseView.setVisibility(View.GONE);
        lastTickAt = SystemClock.elapsedRealtime();
        handler.post(tick);
    }

    void endTurn(boolean timeUp) {
        running = false;
        paused = false;
        handler.removeCallbacks(tick);
        if (timeUp) {
            beep(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 900);
            vibrate(700);
        }
        saveDeck();
        showSummary(timeUp);
    }

    // ------------------------------------------------------------------ summary

    void showSummary(boolean timeUp) {
        screen = SCR_SUMMARY;
        LinearLayout col = column();
        col.setPadding(dp(20), dp(28), dp(20), 0);

        col.addView(text(timeUp ? "¡SE ACABÓ EL TIEMPO!" : "FIN DEL TURNO", 13, YELLOW, W_BOLD));
        LinearLayout head = row();
        TextView team = text(teamNames[currentTeam], 32, CREAM, W_BOLD);
        head.addView(team, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView total = text("", 44, TEAM_COLORS[currentTeam], W_BOLD);
        head.addView(total);
        col.addView(head, matchWrap(0, dp(2), 0, dp(14)));

        LinearLayout stats = row();
        final TextView[] statNums = new TextView[3];
        String[] statLabels = {"aciertos", "tabú", "pasadas"};
        int[] statColors = {MINT, CORAL_TEXT, MUTED};
        for (int i = 0; i < 3; i++) {
            LinearLayout tile = column();
            tile.setPadding(dp(14), dp(10), dp(14), dp(12));
            tile.setBackgroundDrawable(round(SURFACE, 16));
            statNums[i] = text("0", 26, statColors[i], W_BOLD);
            tile.addView(statNums[i]);
            tile.addView(text(statLabels[i], 13, MUTED, W_REGULAR));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i < 2) lp.rightMargin = dp(8);
            stats.addView(tile, lp);
        }
        col.addView(stats, matchWrap(0, 0, 0, dp(16)));

        TextView hint = text(turnLog.isEmpty() ? "No se jugó ninguna palabra." : "¿Algo quedó mal marcado? Toca la palabra para cambiarla.", 14, MUTED, W_REGULAR);
        col.addView(hint, matchWrap(0, 0, 0, dp(10)));

        final Runnable refresh = new Runnable() {
            public void run() {
                int[] counts = new int[3];
                for (int[] e : turnLog) counts[e[1] - 1]++;
                for (int i = 0; i < 3; i++) statNums[i].setText(String.valueOf(counts[i]));
                int p = turnPoints();
                total.setText(p > 0 ? "+" + p : p < 0 ? "−" + (-p) : "0");
            }
        };

        LinearLayout list = column();
        for (int i = 0; i < turnLog.size(); i++) {
            final int[] entry = turnLog.get(i);
            final LinearLayout item = row();
            item.setPadding(dp(14), 0, dp(16), 0);
            item.setClickable(true);
            item.setBackgroundDrawable(pressable(SURFACE, 16));
            final TextView badge = text("", 16, INK, W_BOLD);
            badge.setGravity(Gravity.CENTER);
            final TextView word = text(Words.card(entry[0])[0].substring(0, 1) + Words.card(entry[0])[0].substring(1).toLowerCase(ES), 18, CREAM, W_SEMI);
            final TextView pts = text("", 17, CREAM, W_BOLD);
            item.addView(badge, new LinearLayout.LayoutParams(dp(30), dp(30)));
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            wlp.leftMargin = dp(12);
            item.addView(word, wlp);
            item.addView(pts);
            styleSummaryRow(badge, word, pts, entry[1]);
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    entry[1] = entry[1] == RES_CORRECT ? RES_TABOO : entry[1] == RES_TABOO ? RES_SKIP : RES_CORRECT;
                    styleSummaryRow(badge, word, pts, entry[1]);
                    refresh.run();
                }
            });
            list.addView(item, matchHeight(dp(56), 0, 0, 0, dp(8)));
        }
        refresh.run();

        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView next = bigButton("Continuar", YELLOW, 22);
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                applyTurn();
            }
        });
        col.addView(next, matchHeight(dp(68), 0, dp(12), 0, dp(16)));
        setContentView(col);
    }

    void styleSummaryRow(TextView badge, TextView word, TextView pts, int result) {
        int color = result == RES_CORRECT ? MINT : result == RES_TABOO ? CORAL : SURFACE2;
        badge.setText(result == RES_CORRECT ? "✓" : result == RES_TABOO ? "✗" : "↷");
        badge.setTextColor(result == RES_SKIP ? MUTED : INK);
        badge.setBackgroundDrawable(round(color, 15));
        word.setTextColor(result == RES_SKIP ? MUTED : CREAM);
        int p = pointsFor(result);
        pts.setText(p > 0 ? "+" + p : p < 0 ? "−" + (-p) : "0");
        pts.setTextColor(result == RES_CORRECT ? MINT : result == RES_TABOO ? CORAL_TEXT : MUTED);
    }

    void applyTurn() {
        scores[currentTeam] += turnPoints();
        turnsPlayed++;
        if (turnsPlayed % numTeams == 0) {
            // A full round is complete: check if someone reached the goal with a clear lead.
            int best = -1, bestScore = Integer.MIN_VALUE, ties = 0;
            for (int i = 0; i < numTeams; i++) {
                if (scores[i] > bestScore) {
                    bestScore = scores[i];
                    best = i;
                    ties = 1;
                } else if (scores[i] == bestScore) {
                    ties++;
                }
            }
            if (bestScore >= targetScore()) {
                if (ties == 1) {
                    showEnd(best);
                    return;
                }
                Toast.makeText(this, "¡Empate! Se juega otra ronda de desempate", Toast.LENGTH_LONG).show();
            }
        }
        currentTeam = (currentTeam + 1) % numTeams;
        showReady();
    }

    // ------------------------------------------------------------------ end

    void showEnd(int winner) {
        screen = SCR_END;
        beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600);

        LinearLayout panel = column();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(24), dp(40), dp(24), dp(32));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(TEAM_COLORS[winner]);
        float r = dp(36);
        pbg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        panel.setBackgroundDrawable(pbg);
        ImageView trophy = new ImageView(this);
        trophy.setImageResource(R.drawable.ic_trophy);
        panel.addView(trophy, new LinearLayout.LayoutParams(dp(88), dp(88)));
        panel.addView(text("¡GANARON!", 15, INK, W_BOLD), wrap(0, dp(8), 0, dp(2)));
        TextView name = text(teamNames[winner], 48, INK, W_BOLD);
        name.setGravity(Gravity.CENTER);
        name.setLineSpacing(0, 0.9f);
        panel.addView(name, matchWrap(0, 0, 0, dp(6)));
        int rounds = turnsPlayed / numTeams;
        panel.addView(text(scores[winner] + " puntos en " + rounds + (rounds == 1 ? " ronda" : " rondas"), 17, INK, W_SEMI), wrap(0, 0, 0, 0));

        Integer[] order = new Integer[numTeams];
        for (int i = 0; i < numTeams; i++) order[i] = i;
        Arrays.sort(order, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return scores[b] - scores[a];
            }
        });
        LinearLayout ranking = column();
        ranking.setPadding(dp(20), dp(24), dp(20), 0);
        for (int k = 0; k < numTeams; k++) {
            int i = order[k];
            boolean first = k == 0;
            LinearLayout item = row();
            item.setPadding(dp(16), 0, dp(16), 0);
            item.setBackgroundDrawable(round(SURFACE, 18));
            TextView pos = text(String.valueOf(k + 1), 16, INK, W_BOLD);
            pos.setGravity(Gravity.CENTER);
            pos.setBackgroundDrawable(round(TEAM_COLORS[i], 16));
            item.addView(pos, new LinearLayout.LayoutParams(dp(32), dp(32)));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            nlp.leftMargin = dp(14);
            item.addView(text(teamNames[i], first ? 19 : 18, CREAM, first ? W_BOLD : W_SEMI), nlp);
            item.addView(text(String.valueOf(scores[i]), first ? 24 : 22, first ? CREAM : MUTED, W_BOLD));
            ranking.addView(item, matchHeight(dp(first ? 60 : 56), 0, 0, 0, dp(10)));
        }

        LinearLayout top = column();
        top.addView(panel, matchWrap(0, 0, 0, 0));
        top.addView(ranking);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(top);

        LinearLayout bottom = column();
        bottom.setPadding(dp(20), dp(8), dp(20), dp(16));
        TextView again = bigButton("Revancha", YELLOW, 22);
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                newGame();
            }
        });
        bottom.addView(again, matchHeight(dp(68), 0, 0, 0, dp(10)));
        TextView menu = bigButton("Cambiar equipos", SURFACE, 17);
        menu.setTextColor(CREAM);
        menu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSetup();
            }
        });
        bottom.addView(menu, matchHeight(dp(56), 0, 0, 0, 0));

        LinearLayout root = column();
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(bottom, matchWrap(0, 0, 0, 0));
        setContentView(root);
    }

    // ------------------------------------------------------------------ feedback

    void beep(int toneType, int ms) {
        if (!soundOn || tone == null) return;
        try {
            tone.startTone(toneType, ms);
        } catch (RuntimeException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    void vibrate(long ms) {
        if (!soundOn || vibrator == null) return;
        try {
            vibrator.vibrate(ms);
        } catch (RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------------ view helpers

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout card() {
        LinearLayout c = column();
        c.setPadding(dp(16), dp(16), dp(16), dp(12));
        c.setBackgroundDrawable(round(SURFACE, 22));
        return c;
    }

    LinearLayout.LayoutParams matchWrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    LinearLayout.LayoutParams wrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    LinearLayout.LayoutParams matchHeight(int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    TextView text(String s, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(fonts[weight]);
        t.setIncludeFontPadding(false);
        return t;
    }

    TextView label(String s) {
        return text(s, 13, MUTED, W_BOLD);
    }

    View dot(int color, int sizeDp) {
        View v = new View(this);
        v.setBackgroundDrawable(round(color, sizeDp / 2f));
        return v;
    }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    static int darker(int c) {
        int a = c >>> 24, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (a << 24) | ((r * 7 / 10) << 16) | ((g * 7 / 10) << 8) | (b * 7 / 10);
    }

    Drawable pressable(int color, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(darker(color), radiusDp));
        s.addState(new int[]{}, round(color, radiusDp));
        return s;
    }

    /** Chunky button with a darker "ledge" underneath that sinks when pressed. */
    TextView bigButton(String label, int color, float sp) {
        int ledge = dp(5);
        LayerDrawable up = new LayerDrawable(new Drawable[]{round(darker(color), 20), round(color, 20)});
        up.setLayerInset(1, 0, 0, 0, ledge);
        LayerDrawable down = new LayerDrawable(new Drawable[]{round(darker(color), 20), round(color, 20)});
        down.setLayerInset(1, 0, ledge, 0, 0);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, down);
        s.addState(new int[]{}, up);
        TextView b = text(label, sp, INK, W_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackgroundDrawable(s);
        b.setPadding(dp(8), 0, dp(8), ledge);
        return b;
    }

    TextView smallButton(String label, String description) {
        TextView b = text(label, 20, CREAM, W_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(description);
        b.setBackgroundDrawable(pressable(SURFACE2, 12));
        return b;
    }

    TextView circleButton(String label, int color, String description) {
        TextView b = text(label, 20, CREAM, W_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(description);
        b.setBackgroundDrawable(pressable(color, 22));
        return b;
    }

    /** Segmented control: an ink track with the selected option as a cream pill. */
    LinearLayout segmented(String[] labels, int selected, final IntCallback cb) {
        LinearLayout track = row();
        track.setPadding(dp(4), dp(4), dp(4), dp(4));
        track.setBackgroundDrawable(round(INK, 14));
        final TextView[] chips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView c = text(labels[i], 15, MUTED, W_SEMI);
            c.setGravity(Gravity.CENTER);
            c.setClickable(true);
            chips[i] = c;
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    for (int j = 0; j < chips.length; j++) styleSegment(chips[j], j == idx);
                    cb.call(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
            if (i < labels.length - 1) lp.rightMargin = dp(4);
            track.addView(c, lp);
            styleSegment(c, i == selected);
        }
        return track;
    }

    void styleSegment(TextView c, boolean on) {
        c.setBackgroundDrawable(on ? round(CREAM, 11) : null);
        c.setTextColor(on ? INK : MUTED);
        c.setTypeface(fonts[on ? W_BOLD : W_SEMI]);
    }

    View switchRow(String title, boolean on, final IntCallback cb) {
        LinearLayout r = row();
        r.setClickable(true);
        r.setMinimumHeight(dp(48));
        r.addView(text(title, 16, CREAM, W_SEMI), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Toggle t = new Toggle(this, on);
        t.setContentDescription(title);
        r.addView(t, new LinearLayout.LayoutParams(dp(52), dp(30)));
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                t.setOn(!t.on);
                cb.call(t.on ? 1 : 0);
            }
        });
        return r;
    }

    /** On/off switch: sunflower when on, muted when off. */
    static class Toggle extends View {
        boolean on;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on;
        }

        void setOn(boolean v) {
            on = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), pad = h * 0.1f;
            paint.setColor(on ? YELLOW : SURFACE2);
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, h / 2, h / 2, paint);
            float d = h - 2 * pad;
            float x = on ? w - pad - d / 2 : pad + d / 2;
            paint.setColor(on ? INK : MUTED);
            c.drawCircle(x, h / 2, d / 2, paint);
        }
    }

    /** Rounded progress bar (turn time left, or a team's progress toward the goal). */
    static class TimeBar extends View {
        float frac = 1f;
        int color = YELLOW;
        final int track;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();

        TimeBar(Context c, int track) {
            super(c);
            this.track = track;
        }

        void set(float f, int col) {
            frac = Math.max(0f, Math.min(1f, f));
            color = col;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            paint.setColor(track);
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, h / 2, h / 2, paint);
            if (frac > 0) {
                paint.setColor(color);
                rect.set(0, 0, Math.max(h, w * frac), h);
                c.drawRoundRect(rect, h / 2, h / 2, paint);
            }
        }
    }
}
