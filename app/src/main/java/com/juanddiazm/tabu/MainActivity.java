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

    static final int BG = 0xFF1E1B3A, SURFACE = 0xFF2C2752, TEXT = 0xFFFFFFFF, MUTED = 0xFFB9B4D9,
            ACCENT = 0xFFFF4D6D, GREEN = 0xFF22C55E, RED = 0xFFEF4444, GREY = 0xFF64748B,
            YELLOW = 0xFFFACC15, INK = 0xFF1F2937;
    static final int[] TEAM_COLORS = {0xFF3B82F6, 0xFFF97316, 0xFF10B981, 0xFFA855F7};
    static final int[] TIME_OPTIONS = {30, 45, 60, 90, 120};
    static final int[] SCORE_OPTIONS = {10, 20, 30, 50};
    static final int RES_CORRECT = 1, RES_TABOO = 2, RES_SKIP = 3;
    static final int SCR_SETUP = 0, SCR_READY = 1, SCR_PLAY = 2, SCR_SUMMARY = 3, SCR_END = 4;
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
    int lastBeepSecond;
    int screen = SCR_SETUP;

    final Handler handler = new Handler();
    ToneGenerator tone;
    Vibrator vibrator;
    SharedPreferences prefs;
    final ArrayList<EditText> nameFields = new ArrayList<EditText>();

    // Play screen views
    TextView timerText, turnPointsText, wordText;
    TextView[] forbiddenViews;
    View cardView, pauseView;
    TimeBar timeBar;

    final Runnable tick = new Runnable() {
        public void run() {
            onTick();
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
        saveDeck();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        if (tone != null) tone.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        switch (screen) {
            case SCR_SETUP:
                super.onBackPressed();
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

        LinearLayout col = column();
        col.setPadding(dp(20), dp(28), dp(20), dp(24));

        TextView title = text("TABÚ", 56, ACCENT, true);
        title.setGravity(Gravity.CENTER);
        col.addView(title);
        TextView sub = text("Haz que tu equipo adivine la palabra\nsin decir las prohibidas", 15, MUTED, false);
        sub.setGravity(Gravity.CENTER);
        col.addView(sub, matchWrap(0, 0, 0, dp(20)));

        // Teams
        LinearLayout teamHeader = new LinearLayout(this);
        teamHeader.setGravity(Gravity.CENTER_VERTICAL);
        teamHeader.addView(text("Equipos", 18, TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView minus = button("−", SURFACE, 22);
        TextView count = text(String.valueOf(numTeams), 22, TEXT, true);
        count.setGravity(Gravity.CENTER);
        TextView plus = button("+", SURFACE, 22);
        teamHeader.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(44)));
        teamHeader.addView(count, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        teamHeader.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(44)));
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
        col.addView(teamHeader, matchWrap(0, dp(8), 0, dp(8)));

        nameFields.clear();
        for (int i = 0; i < numTeams; i++) {
            EditText et = new EditText(this);
            et.setText(teamNames[i]);
            et.setSingleLine(true);
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(18)});
            et.setTextColor(TEXT);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            et.setTypeface(Typeface.DEFAULT_BOLD);
            et.setPadding(dp(16), dp(12), dp(16), dp(12));
            GradientDrawable bg = round(SURFACE, 14);
            bg.setStroke(dp(3), TEAM_COLORS[i]);
            et.setBackgroundDrawable(bg);
            nameFields.add(et);
            col.addView(et, matchWrap(0, 0, 0, dp(10)));
        }

        // Time
        col.addView(text("Tiempo por turno", 18, TEXT, true), matchWrap(0, dp(14), 0, dp(8)));
        String[] timeLabels = new String[TIME_OPTIONS.length];
        for (int i = 0; i < TIME_OPTIONS.length; i++) timeLabels[i] = TIME_OPTIONS[i] + "s";
        col.addView(optionRow(timeLabels, timeIdx, new IntCallback() {
            public void call(int i) {
                timeIdx = i;
            }
        }), matchWrap(0, 0, 0, 0));

        // Score
        col.addView(text("Puntos para ganar", 18, TEXT, true), matchWrap(0, dp(18), 0, dp(8)));
        String[] scoreLabels = new String[SCORE_OPTIONS.length];
        for (int i = 0; i < SCORE_OPTIONS.length; i++) scoreLabels[i] = String.valueOf(SCORE_OPTIONS[i]);
        col.addView(optionRow(scoreLabels, scoreIdx, new IntCallback() {
            public void call(int i) {
                scoreIdx = i;
            }
        }), matchWrap(0, 0, 0, 0));

        // Skip penalty
        col.addView(text("Pasar palabra resta 1 punto", 18, TEXT, true), matchWrap(0, dp(18), 0, dp(8)));
        col.addView(optionRow(new String[]{"No", "Sí"}, skipPenalty ? 1 : 0, new IntCallback() {
            public void call(int i) {
                skipPenalty = i == 1;
            }
        }), matchWrap(0, 0, 0, 0));

        // Sound
        col.addView(text("Sonido y vibración", 18, TEXT, true), matchWrap(0, dp(18), 0, dp(8)));
        col.addView(optionRow(new String[]{"No", "Sí"}, soundOn ? 1 : 0, new IntCallback() {
            public void call(int i) {
                soundOn = i == 1;
            }
        }), matchWrap(0, 0, 0, 0));

        TextView play = button("¡JUGAR!", ACCENT, 24);
        play.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                readNames();
                saveSettings();
                newGame();
            }
        });
        col.addView(play, matchHeight(dp(64), 0, dp(28), 0, dp(12)));

        TextView rules = button("Cómo se juega", SURFACE, 17);
        rules.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showRules();
            }
        });
        col.addView(rules, matchHeight(dp(52), 0, 0, 0, 0));

        TextView info = text(Words.CARDS.length + " tarjetas", 13, MUTED, false);
        info.setGravity(Gravity.CENTER);
        col.addView(info, matchWrap(0, dp(16), 0, 0));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col);
        setContentView(sv);
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
                .setMessage("• Formen 2 a 4 equipos y siéntense intercalados.\n\n"
                        + "• En cada turno, una persona toma el celular y describe la palabra grande "
                        + "para que su equipo la adivine, SIN decir ninguna de las palabras prohibidas "
                        + "(ni partes de ellas, ni la palabra misma).\n\n"
                        + "• Prohibido hacer gestos, sonidos, deletrear o decir \"suena como…\".\n\n"
                        + "• Un integrante de otro equipo mira la pantalla y vigila: si se dice una "
                        + "palabra prohibida, presiona TABÚ (−1 punto).\n\n"
                        + "• ¡CORRECTO! suma 1 punto. PASAR salta la palabra (puede restar 1 si lo activas).\n\n"
                        + "• Al acabar el tiempo, el celular pasa al siguiente equipo con una nueva persona describiendo.\n\n"
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

        LinearLayout col = column();
        col.setPadding(dp(20), dp(24), dp(20), dp(20));

        TextView round = text("RONDA " + (turnsPlayed / numTeams + 1) + "  ·  META " + targetScore() + " PUNTOS", 14, MUTED, true);
        round.setGravity(Gravity.CENTER);
        col.addView(round);

        TextView turnOf = text("Turno de", 20, TEXT, false);
        turnOf.setGravity(Gravity.CENTER);
        col.addView(turnOf, matchWrap(0, dp(18), 0, 0));

        TextView team = text(teamNames[currentTeam], 40, TEAM_COLORS[currentTeam], true);
        team.setGravity(Gravity.CENTER);
        col.addView(team, matchWrap(0, 0, 0, dp(16)));

        TextView hint = text("Pasa el celular a quien va a describir. Su equipo adivina; "
                + "alguien del otro equipo vigila las palabras prohibidas.", 16, TEXT, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), dp(14), dp(16), dp(14));
        hint.setBackgroundDrawable(round(SURFACE, 16));
        col.addView(hint, matchWrap(0, 0, 0, dp(18)));

        col.addView(scoreboard(currentTeam), matchWrap(0, 0, 0, 0));

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        TextView start = button("¡EMPEZAR!", ACCENT, 26);
        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startTurn();
            }
        });
        col.addView(start, matchHeight(dp(72), 0, dp(20), 0, dp(10)));

        TextView quit = button("Salir al menú", BG, 15);
        quit.setTextColor(MUTED);
        quit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmQuit();
            }
        });
        col.addView(quit, matchHeight(dp(44), 0, 0, 0, 0));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col);
        setContentView(sv);
    }

    View scoreboard(int highlight) {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(10), dp(16), dp(10));
        box.setBackgroundDrawable(round(SURFACE, 16));
        for (int i = 0; i < numTeams; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            View dot = new View(this);
            dot.setBackgroundDrawable(round(TEAM_COLORS[i], 7));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(14), dp(14));
            dlp.rightMargin = dp(12);
            row.addView(dot, dlp);
            TextView name = text(teamNames[i], 18, i == highlight ? TEXT : MUTED, i == highlight);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(text(String.valueOf(scores[i]), 22, i == highlight ? TEXT : MUTED, true));
            box.addView(row);
        }
        return box;
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

    // ------------------------------------------------------------------ play screen

    void startTurn() {
        turnLog.clear();
        remainingMs = turnSeconds() * 1000L;
        running = true;
        paused = false;
        lastBeepSecond = -1;
        lastMarkAt = 0;
        showPlay();
        nextWord();
        lastTickAt = SystemClock.elapsedRealtime();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void showPlay() {
        screen = SCR_PLAY;
        int teamColor = TEAM_COLORS[currentTeam];

        LinearLayout col = column();
        col.setPadding(dp(16), dp(12), dp(16), dp(16));

        // Top bar: team, timer, pause
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView team = text(teamNames[currentTeam], 15, TEXT, true);
        team.setSingleLine(true);
        team.setPadding(dp(12), dp(6), dp(12), dp(6));
        team.setBackgroundDrawable(round(teamColor, 20));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        top.addView(wrapLeft(team), tlp);
        timerText = text("", 44, TEXT, true);
        timerText.setGravity(Gravity.CENTER);
        top.addView(timerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView pause = button("❚❚", SURFACE, 18);
        pause.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pauseTurn();
            }
        });
        LinearLayout pauseWrap = new LinearLayout(this);
        pauseWrap.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        pauseWrap.addView(pause, new LinearLayout.LayoutParams(dp(56), dp(48)));
        top.addView(pauseWrap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(top);

        timeBar = new TimeBar(this);
        col.addView(timeBar, matchHeight(dp(10), 0, dp(6), 0, dp(6)));

        turnPointsText = text("", 16, MUTED, true);
        turnPointsText.setGravity(Gravity.CENTER);
        col.addView(turnPointsText, matchWrap(0, 0, 0, dp(10)));

        // Card
        FrameLayout frame = new FrameLayout(this);
        LinearLayout card = column();
        card.setBackgroundDrawable(round(0xFFFFFFFF, 24));
        LinearLayout head = column();
        GradientDrawable headBg = new GradientDrawable();
        headBg.setColor(teamColor);
        float r = dp(24);
        headBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        head.setBackgroundDrawable(headBg);
        head.setPadding(dp(16), dp(14), dp(16), dp(18));
        TextView guess = text("DESCRIBE", 13, 0xCCFFFFFF, true);
        guess.setGravity(Gravity.CENTER);
        head.addView(guess);
        wordText = text("", 34, TEXT, true);
        wordText.setGravity(Gravity.CENTER);
        head.addView(wordText);
        card.addView(head, matchWrap(0, 0, 0, 0));

        TextView noSay = text("NO PUEDES DECIR", 13, RED, true);
        noSay.setGravity(Gravity.CENTER);
        card.addView(noSay, matchWrap(0, dp(14), 0, dp(4)));

        LinearLayout forb = column();
        forb.setGravity(Gravity.CENTER);
        forbiddenViews = new TextView[5];
        for (int i = 0; i < 5; i++) {
            TextView f = text("", 24, INK, true);
            f.setGravity(Gravity.CENTER);
            forbiddenViews[i] = f;
            forb.addView(f, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        forb.setPadding(dp(12), 0, dp(12), dp(14));
        card.addView(forb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        cardView = card;
        frame.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pauseView = buildPauseOverlay();
        pauseView.setVisibility(View.GONE);
        frame.addView(pauseView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Action buttons
        LinearLayout actions = new LinearLayout(this);
        actions.setBaselineAligned(false); // keep 1- and 2-line buttons top-aligned
        TextView taboo = button("TABÚ\n−1", RED, 20);
        TextView skip = button("PASAR" + (skipPenalty ? "\n−1" : ""), GREY, 18);
        TextView ok = button("¡CORRECTO!\n+1", GREEN, 20);
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
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mark(RES_CORRECT);
            }
        });
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, dp(92), 1f);
        a1.rightMargin = dp(8);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, dp(92), 0.8f);
        a2.rightMargin = dp(8);
        LinearLayout.LayoutParams a3 = new LinearLayout.LayoutParams(0, dp(92), 1.4f);
        actions.addView(taboo, a1);
        actions.addView(skip, a2);
        actions.addView(ok, a3);
        col.addView(actions, matchWrap(0, dp(14), 0, 0));

        setContentView(col);
        updateTimerViews();
        updateTurnPoints();
    }

    View buildPauseOverlay() {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        box.setBackgroundDrawable(round(SURFACE, 24));
        box.setClickable(true);
        TextView t = text("PAUSA", 40, TEXT, true);
        t.setGravity(Gravity.CENTER);
        box.addView(t, matchWrap(0, 0, 0, dp(24)));
        TextView resume = button("Continuar", GREEN, 20);
        resume.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                resumeTurn();
            }
        });
        box.addView(resume, matchHeight(dp(60), 0, 0, 0, dp(12)));
        TextView end = button("Terminar turno", GREY, 17);
        end.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                endTurn(false);
            }
        });
        box.addView(end, matchHeight(dp(52), 0, 0, 0, dp(12)));
        TextView quit = button("Salir al menú", RED, 17);
        quit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmQuit();
            }
        });
        box.addView(quit, matchHeight(dp(52), 0, 0, 0, 0));
        return box;
    }

    void nextWord() {
        currentCard = drawCard();
        String[] c = Words.card(currentCard);
        wordText.setText(c[0]);
        wordText.setTextSize(TypedValue.COMPLEX_UNIT_SP, c[0].length() > 14 ? 26 : c[0].length() > 10 ? 30 : 36);
        for (int i = 0; i < 5; i++) {
            forbiddenViews[i].setText(i + 1 < c.length ? c[i + 1].toUpperCase(ES) : "");
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
        updateTurnPoints();
        nextWord();
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
        timerText.setTextColor(secs <= 10 ? RED : TEXT);
        float frac = remainingMs / (turnSeconds() * 1000f);
        timeBar.set(frac, frac > 0.5f ? GREEN : frac > 0.2f ? YELLOW : RED);
    }

    void updateTurnPoints() {
        int correct = 0;
        for (int[] e : turnLog) if (e[1] == RES_CORRECT) correct++;
        int pts = turnPoints();
        turnPointsText.setText("Aciertos: " + correct + "   ·   Puntos del turno: " + (pts > 0 ? "+" : "") + pts);
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
        col.setPadding(dp(20), dp(24), dp(20), dp(16));

        TextView title = text(timeUp ? "¡TIEMPO!" : "FIN DEL TURNO", 40, ACCENT, true);
        title.setGravity(Gravity.CENTER);
        col.addView(title);

        final TextView total = text("", 24, TEAM_COLORS[currentTeam], true);
        total.setGravity(Gravity.CENTER);
        col.addView(total, matchWrap(0, dp(4), 0, dp(4)));

        TextView hint = text(turnLog.isEmpty() ? "No se jugó ninguna palabra." : "Toca una palabra para corregir su resultado", 14, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        col.addView(hint, matchWrap(0, 0, 0, dp(12)));

        LinearLayout list = column();
        for (int i = 0; i < turnLog.size(); i++) {
            final int[] entry = turnLog.get(i);
            final LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setClickable(true);
            final TextView icon = text("", 20, TEXT, true);
            icon.setGravity(Gravity.CENTER);
            final TextView word = text(Words.card(entry[0])[0], 18, TEXT, true);
            final TextView pts = text("", 18, TEXT, true);
            row.addView(icon, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            wlp.leftMargin = dp(8);
            row.addView(word, wlp);
            row.addView(pts);
            styleSummaryRow(row, icon, pts, entry[1]);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    entry[1] = entry[1] == RES_CORRECT ? RES_TABOO : entry[1] == RES_TABOO ? RES_SKIP : RES_CORRECT;
                    styleSummaryRow(row, icon, pts, entry[1]);
                    updateSummaryTotal(total);
                }
            });
            list.addView(row, matchWrap(0, 0, 0, dp(8)));
        }
        updateSummaryTotal(total);

        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView next = button("CONTINUAR", ACCENT, 22);
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                applyTurn();
            }
        });
        col.addView(next, matchHeight(dp(64), 0, dp(12), 0, 0));
        setContentView(col);
    }

    void styleSummaryRow(View row, TextView icon, TextView pts, int result) {
        int color = result == RES_CORRECT ? GREEN : result == RES_TABOO ? RED : GREY;
        icon.setText(result == RES_CORRECT ? "✓" : result == RES_TABOO ? "✗" : "↷");
        icon.setTextColor(color);
        int p = pointsFor(result);
        pts.setText(p > 0 ? "+" + p : String.valueOf(p));
        pts.setTextColor(color);
        GradientDrawable bg = round(SURFACE, 14);
        bg.setStroke(dp(2), color);
        row.setBackgroundDrawable(bg);
    }

    void updateSummaryTotal(TextView total) {
        int p = turnPoints();
        total.setText(teamNames[currentTeam] + ": " + (p > 0 ? "+" : "") + p + (Math.abs(p) == 1 ? " punto" : " puntos"));
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
        LinearLayout col = column();
        col.setPadding(dp(20), dp(32), dp(20), dp(20));
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView trophy = text("🏆", 72, TEXT, false);
        trophy.setGravity(Gravity.CENTER);
        col.addView(trophy, matchWrap(0, 0, 0, 0));
        TextView won = text("¡GANADOR!", 22, MUTED, true);
        won.setGravity(Gravity.CENTER);
        col.addView(won, matchWrap(0, dp(8), 0, 0));
        TextView name = text(teamNames[winner], 42, TEAM_COLORS[winner], true);
        name.setGravity(Gravity.CENTER);
        col.addView(name, matchWrap(0, 0, 0, dp(24)));

        // Sorted scoreboard
        Integer[] order = new Integer[numTeams];
        for (int i = 0; i < numTeams; i++) order[i] = i;
        Arrays.sort(order, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return scores[b] - scores[a];
            }
        });
        LinearLayout box = column();
        box.setPadding(dp(16), dp(10), dp(16), dp(10));
        box.setBackgroundDrawable(round(SURFACE, 16));
        for (int k = 0; k < numTeams; k++) {
            int i = order[k];
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            row.addView(text((k + 1) + ".", 18, MUTED, true), new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(text(teamNames[i], 18, TEAM_COLORS[i], true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(text(String.valueOf(scores[i]), 22, TEXT, true));
            box.addView(row);
        }
        col.addView(box, matchWrap(0, 0, 0, 0));

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        TextView again = button("REVANCHA", ACCENT, 22);
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                newGame();
            }
        });
        col.addView(again, matchHeight(dp(64), 0, dp(24), 0, dp(12)));
        TextView menu = button("Menú", SURFACE, 18);
        menu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSetup();
            }
        });
        col.addView(menu, matchHeight(dp(52), 0, 0, 0, 0));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col);
        setContentView(sv);
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

    View wrapLeft(View v) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        l.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    LinearLayout.LayoutParams matchWrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    LinearLayout.LayoutParams matchHeight(int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    static int darker(int c) {
        int a = c >>> 24, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (a << 24) | ((r * 3 / 4) << 16) | ((g * 3 / 4) << 8) | (b * 3 / 4);
    }

    Drawable pressable(int color, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(darker(color), radiusDp));
        s.addState(new int[]{}, round(color, radiusDp));
        return s;
    }

    TextView button(String label, int color, float sp) {
        TextView b = text(label, sp, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackgroundDrawable(pressable(color, 16));
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        return b;
    }

    LinearLayout optionRow(String[] labels, int selected, final IntCallback cb) {
        LinearLayout row = new LinearLayout(this);
        final TextView[] chips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView c = text(labels[i], 17, TEXT, true);
            c.setGravity(Gravity.CENTER);
            c.setClickable(true);
            chips[i] = c;
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    for (int j = 0; j < chips.length; j++) styleChip(chips[j], j == idx);
                    cb.call(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            if (i < labels.length - 1) lp.rightMargin = dp(8);
            row.addView(c, lp);
            styleChip(c, i == selected);
        }
        return row;
    }

    void styleChip(TextView c, boolean on) {
        GradientDrawable g = round(on ? ACCENT : SURFACE, 14);
        c.setBackgroundDrawable(g);
        c.setTextColor(on ? TEXT : MUTED);
    }

    /** Rounded progress bar for the remaining time. */
    static class TimeBar extends View {
        float frac = 1f;
        int color = GREEN;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();

        TimeBar(Context c) {
            super(c);
        }

        void set(float f, int col) {
            frac = Math.max(0f, Math.min(1f, f));
            color = col;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            paint.setColor(0x33FFFFFF);
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
