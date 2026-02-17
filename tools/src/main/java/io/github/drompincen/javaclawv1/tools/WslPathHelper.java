package io.github.drompincen.javaclawv1.tools;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility for translating between WSL and Windows file paths.
 */
public class WslPathHelper {

    private static final boolean IS_WSL = detectWsl();

    private static boolean detectWsl() {
        try {
            String version = Files.readString(Path.of("/proc/version"));
            return version.toLowerCase().contains("microsoft") || version.toLowerCase().contains("wsl");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isWsl() { return IS_WSL; }

    /**
     * Resolve a path that may be Windows (C:\...) or WSL (/mnt/c/...).
     * Translates as needed based on the current environment.
     */
    public static Path resolve(String inputPath) {
        if (inputPath == null) return Path.of(".");
        inputPath = inputPath.trim();

        if (IS_WSL) {
            // Running in WSL: translate Windows paths → WSL paths
            if (inputPath.length() >= 3 && inputPath.charAt(1) == ':' && (inputPath.charAt(2) == '\\' || inputPath.charAt(2) == '/')) {
                char drive = Character.toLowerCase(inputPath.charAt(0));
                String rest = inputPath.substring(2).replace('\\', '/');
                return Path.of("/mnt/" + drive + rest);
            }
            // Handle bare drive letter like C:file.txt
            if (inputPath.length() >= 2 && inputPath.charAt(1) == ':') {
                char drive = Character.toLowerCase(inputPath.charAt(0));
                String rest = inputPath.substring(2).replace('\\', '/');
                return Path.of("/mnt/" + drive + "/" + rest);
            }
        } else if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            // Running in Windows: translate WSL paths → Windows paths
            if (inputPath.startsWith("/mnt/") && inputPath.length() > 6 && inputPath.charAt(6) == '/') {
                char drive = Character.toUpperCase(inputPath.charAt(5));
                String rest = inputPath.substring(6).replace('/', '\\');
                return Path.of(drive + ":" + rest);
            }
        }

        return Path.of(inputPath);
    }

    /**
     * Convert a WSL path to Windows path using wslpath command.
     */
    public static String toWindowsPath(String wslPath) {
        try {
            Process p = new ProcessBuilder("wslpath", "-w", wslPath).start();
            String result = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() == 0 && !result.isEmpty()) return result;
        } catch (Exception ignored) {}
        return wslPath;
    }

    /**
     * Convert a Windows path to WSL path using wslpath command.
     */
    public static String toWslPath(String windowsPath) {
        try {
            Process p = new ProcessBuilder("wslpath", "-u", windowsPath).start();
            String result = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() == 0 && !result.isEmpty()) return result;
        } catch (Exception ignored) {}
        return windowsPath;
    }
}
