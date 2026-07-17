package ac.boar.anticheat.check.api;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.violation.Violation;
import ac.boar.api.anticheat.annotations.CheckInfo;
import ac.boar.api.anticheat.annotations.Experimental;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BaseCheck implements Check {
    protected final BoarPlayer player;

    private final String name, type;
    private final boolean experimental;
    private final Map<String, BaseCheck> typedChecks = new ConcurrentHashMap<>();
    private int vl = 0;

    // How much evidence we need before alerting and how fast legit behaviour clears that evidence out.
    // Setbacks/cancels never wait for this, only the alert does. This way a one-off misprediction
    // (floating point noise, simulation edge cases, ...) never reaches the chat/logs, while actual
    // cheats fail over and over again and still alert almost instantly.
    private float bufferLimit = 1.0F, bufferDecay = 0.25F;
    private float buffer;

    private long lastAlert;

    public BaseCheck(BoarPlayer player) {
        this.player = player;
        this.name = getClass().getDeclaredAnnotation(CheckInfo.class).name();
        this.type = getClass().getDeclaredAnnotation(CheckInfo.class).type();
        this.experimental = getClass().getDeclaredAnnotation(Experimental.class) != null;
    }

    public BaseCheck(BoarPlayer player, String name, String type, boolean experimental) {
        this.player = player;
        this.name = name;
        this.type = type;
        this.experimental = experimental;
    }

    public BaseCheck(BoarPlayer player, String name, String type, boolean experimental, float bufferLimit, float bufferDecay) {
        this(player, name, type, experimental);
        this.buffer(bufferLimit, bufferDecay);
    }

    protected final void buffer(float limit, float decay) {
        this.bufferLimit = limit;
        this.bufferDecay = decay;
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public String type() {
        return this.type;
    }

    @Override
    public boolean experimental() {
        return this.experimental;
    }

    public int vl() {
        return this.vl;
    }

    public final void reward() {
        this.reward(this.bufferDecay);
    }

    public final void reward(float amount) {
        this.buffer = Math.max(0, this.buffer - amount);
    }

    @Override
    public void fail() {
        fail("");
    }

    @Override
    public void fail(String verbose) {
        this.fail(1.0F, verbose);
    }

    /**
     * @param weight how much this violation counts towards the buffer, use a smaller weight for
     *               failures that are more likely to be caused by de-sync than by cheating.
     * @return true if the violation got past the buffer (and therefore counted), false if it only got buffered.
     */
    public boolean fail(float weight, String verbose) {
        // Cap the buffer so a cheater that stops cheating doesn't keep alerting forever, vl already keeps the history.
        this.buffer = Math.min(this.bufferLimit * 2.0F, this.buffer + weight);
        if (this.buffer < this.bufferLimit) {
            return false;
        }

        this.vl++;

        // Keep counting violations, just don't let a single player flood the chat/logs.
        final long now = System.currentTimeMillis();
        if (now - this.lastAlert < Boar.getConfig().alertCooldown()) {
            return true;
        }
        this.lastAlert = now;

        Boar.getInstance().getViolationRegistry().dispatch(new Violation(this.player, this, this.vl, verbose));
        return true;
    }

    public void fail(Enum<?> type, String verbose) {
        fail(type == null ? null : type.name(), verbose);
    }

    public void fail(String type, String verbose) {
        String typedKey = type == null ? "" : type;
        String disabledName = typedKey.isEmpty() ? this.name : this.name + "-" + typedKey;
        if (Boar.getConfig().disabledChecks().contains(disabledName)) {
            return;
        }

        typedChecks.computeIfAbsent(typedKey, key -> new BaseCheck(this.player, this.name, key, this.experimental)).fail(verbose);
    }

    protected final String getDisplayName() {
        return player.getSession().name();
    }
}
