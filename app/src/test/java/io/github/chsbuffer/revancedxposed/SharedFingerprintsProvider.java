package io.github.chsbuffer.revancedxposed;

import java.util.ArrayList;
import java.util.List;

public class SharedFingerprintsProvider {
    public static List<String> getSharedFingerprints(String app) {
        return switch (app) {
            default -> new ArrayList<>();
        };
    }
}
