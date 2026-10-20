package fxdriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

record AgentArtifact(String path, String sha256, String error) {
    static AgentArtifact capture(final Class<?> entryPoint) {
        Path source = null;
        try {
            final var location = entryPoint.getProtectionDomain().getCodeSource();
            if (location == null) return new AgentArtifact(null, null, "NoCodeSource");
            source = Path.of(location.getLocation().toURI()).toRealPath();
            return Files.isRegularFile(source)
                    ? new AgentArtifact(source.toString(), checksum(source), null)
                    : new AgentArtifact(source.toString(), null, "ClassesDirectory");
        } catch (final Exception failure) {
            return new AgentArtifact(
                    source == null ? null : source.toString(),
                    null,
                    failure.getClass().getSimpleName());
        }
    }

    static String checksum(final Path path) throws IOException {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Required SHA-256 algorithm unavailable", unavailable);
        }
    }

    Map<String, Object> fields() {
        final var fields = new LinkedHashMap<String, Object>();
        fields.put("agentJar", path);
        fields.put("agentSha256", sha256);
        if (error != null) fields.put("artifactIdentityError", error);
        return fields;
    }
}
