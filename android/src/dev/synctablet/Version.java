package dev.synctablet;

/** Comparar versiones y leer SHA256SUMS. Java puro para probarlo en el PC. */
final class Version {
    private Version() {
    }

    private static int[] parts(String v) {
        String[] s = v.replaceFirst("^[vV]", "").split("[.-]");
        int[] n = new int[3];
        for (int i = 0; i < 3 && i < s.length; i++) {
            try {
                n[i] = Integer.parseInt(s[i]);
            } catch (NumberFormatException e) {
                return new int[] {-1, 0, 0};
            }
        }
        return n;
    }

    /** ¿`candidate` es posterior a `installed`? Una versión ilegible nunca lo es. */
    static boolean newer(String candidate, String installed) {
        int[] a = parts(candidate), b = parts(installed);
        if (a[0] < 0 || b[0] < 0) return false;
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }

    /** La suma de `name` en un archivo SHA256SUMS; null si no aparece. */
    static String sum(String sums, String name) {
        for (String line : sums.split("\n")) {
            String[] p = line.trim().split("\\s+\\*?", 2);
            if (p.length == 2 && p[1].equals(name) && p[0].matches("[0-9a-fA-F]{64}")) return p[0].toLowerCase(java.util.Locale.ROOT);
        }
        return null;
    }
}
