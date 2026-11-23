package com.huawei.ascend.sit.judge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identity of the object under test. A version number alone does not identify bytes: the same coordinates can
 * resolve to a published artifact or to a source build with different content, so the checksum is recorded and
 * a verdict only holds for the artifact it names.
 *
 * @param role           what the artifact is in this run (for example "DA process", "gate extension")
 * @param coordinates    Maven coordinates or another declared name
 * @param location       file actually loaded or launched
 * @param sha256         content checksum; {@code null} when the artifact could not be identified
 * @param sourceRevision build source as declared by provisioning; {@code null} when not recorded
 * @param note           why identification failed, or other provenance remarks
 */
public record ArtifactIdentity(String role, String coordinates, String location, String sha256,
                               String sourceRevision, String note) {
    public ArtifactIdentity {
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role must not be blank");
        if (coordinates == null || coordinates.isBlank()) throw new IllegalArgumentException("coordinates must not be blank");
        if (sha256 != null && !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 must be 64 lowercase hex");
        if (sha256 == null && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("an unidentified artifact must state why");
        }
    }

    public static ArtifactIdentity ofFile(String role, String coordinates, Path file, String sourceRevision)
            throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("artifact file missing: " + file);
        return new ArtifactIdentity(role, coordinates, file.toAbsolutePath().toString(), sha256(file),
                blankToNull(sourceRevision), null);
    }

    public static ArtifactIdentity unidentified(String role, String coordinates, String reason) {
        return new ArtifactIdentity(role, coordinates, null, null, null, reason);
    }

    public boolean identified() {
        return sha256 != null;
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
