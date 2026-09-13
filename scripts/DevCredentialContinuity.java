import dev.codespire.encryption.EncryptionService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/** Local read-only rollout proof. Plaintext exists only in memory; the baseline is Tink-encrypted. */
class DevCredentialContinuity {
    private static final String AAD = "m3-dev-credential-continuity";

    public static void main(String[] args) throws Exception {
        try {
            compareOrCapture(args);
        } catch (Exception failure) {
            // JDBC/config exceptions can contain connection details. Never echo their messages here.
            System.err.println("Credential continuity proof failed; no credentials were printed.");
            System.exit(1);
        }
    }

    private static void compareOrCapture(String[] args) throws Exception {
        EncryptionService encryption = new EncryptionService(System.getenv("SPIRE_ENCRYPTION_KEYSET"));
        Properties current = read(encryption);
        Path file = Path.of(args[1]);
        if ("Capture".equals(args[0])) {
            if (Files.exists(file)) throw new IllegalStateException("Baseline already exists");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            current.store(bytes, "Real dev credential/reference continuity");
            Files.write(file, encryption.encrypt(bytes.toByteArray(), AAD), java.nio.file.StandardOpenOption.CREATE_NEW);
            System.out.println("Captured " + current.size() + " real credential/reference entries in an encrypted baseline.");
            return;
        }
        Properties baseline = new Properties();
        baseline.load(new ByteArrayInputStream(encryption.decrypt(Files.readAllBytes(file), AAD)));
        if (!baseline.equals(current)) {
            long missing = baseline.keySet().stream().filter(key -> !current.containsKey(key)).count();
            long added = current.keySet().stream().filter(key -> !baseline.containsKey(key)).count();
            long changed = baseline.keySet().stream().filter(current::containsKey)
                    .filter(key -> !baseline.get(key).equals(current.get(key))).count();
            System.err.printf("Continuity mismatch: %d missing, %d added, %d changed entries.%n", missing, added, changed);
            throw new IllegalStateException("Mismatch");
        }
        System.out.println("PASS: all " + current.size() + " real credential/reference entries are identical after decryption.");
    }

    private static Properties read(EncryptionService encryption) throws Exception {
        String url = "jdbc:postgresql://localhost:" + System.getenv().getOrDefault("POSTGRES_PORT", "34432")
                + "/" + System.getenv("POSTGRES_DB");
        Properties entries = new Properties();
        try (Connection connection = DriverManager.getConnection(url, System.getenv("POSTGRES_USER"),
                System.getenv("POSTGRES_PASSWORD"))) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                    "SELECT id, auth_secret FROM orchestrator.scm_provider ORDER BY id")) {
                while (rows.next()) {
                    String id = rows.getString("id");
                    entries.setProperty("account:" + id, encryption.decryptString(rows.getString("auth_secret"), "provider:" + id));
                }
            }
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                    "SELECT id, account_id, auth_secret FROM orchestrator.context_provider ORDER BY id")) {
                while (rows.next()) {
                    String id = rows.getString("id");
                    String secret = rows.getString("auth_secret");
                    String account = rows.getString("account_id");
                    entries.setProperty("context-account:" + id, account == null ? "" : account);
                    if (secret != null) entries.setProperty("context-secret:" + id,
                            encryption.decryptString(secret, "context-provider:" + id));
                }
            }
            connection.commit();
        }
        if (entries.isEmpty()) throw new IllegalStateException("An empty database is not continuity proof");
        return entries;
    }
}
