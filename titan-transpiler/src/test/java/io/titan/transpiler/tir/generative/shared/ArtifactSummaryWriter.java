package io.titan.transpiler.tir.generative.shared;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ArtifactSummaryWriter {

    private ArtifactSummaryWriter() {
    }

    public static void write(
            Path artifactDir,
            String title,
            String status,
            Map<String, String> fields,
            List<String> notableFiles
    ) throws IOException {
        StringBuilder summary = new StringBuilder();
        summary.append(title).append(System.lineSeparator());
        summary.append(System.lineSeparator());
        summary.append("Status: ").append(status).append(System.lineSeparator());
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            summary.append(entry.getKey()).append(": ").append(entry.getValue()).append(System.lineSeparator());
        }
        if (!notableFiles.isEmpty()) {
            summary.append(System.lineSeparator());
            summary.append("Notable files:").append(System.lineSeparator());
            for (String notableFile : notableFiles) {
                summary.append("- ").append(notableFile).append(System.lineSeparator());
            }
        }
        Files.writeString(artifactDir.resolve("artifact-summary.txt"), summary.toString());
    }
}
