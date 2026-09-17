package site.vackstudio.vplaytime.reward;

/**
 * Load-time environment answers for preflight validation. Implemented by
 * the server (command map + plugin manager probes) in production and by
 * fakes in unit tests, so validation stays pure: no Bukkit calls inside
 * the validator, no I/O, and — critically — nothing here is consulted on
 * the runtime claim path. Claims use only the immutable validated plan.
 *
 * <p>On Bukkit, command registration is the installation evidence: a root
 * is "available" when an enabled plugin (or vanilla) currently provides it.
 * Providers that enable after VPlayTime are picked up by the next
 * {@code /vplaytime reload}, which re-probes and can reactivate the system.
 */
public interface PreflightEnv {

    /**
     * Whether any enabled plugin or vanilla currently provides this command
     * root (lowercase, no slash, {@code plugin:} prefix already stripped).
     */
    boolean commandRegistered(String root);
}
