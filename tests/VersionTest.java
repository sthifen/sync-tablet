package dev.synctablet;

public class VersionTest {
    static int fails;
    static void expect(String n, boolean ok) { if (!ok) { fails++; System.out.println("✗ " + n); } }
    public static void main(String[] a) {
        expect("parche", Version.newer("0.2.2", "0.2.1"));
        expect("con v", Version.newer("v0.3.0", "0.2.9"));
        expect("dos cifras", Version.newer("0.10.0", "0.9.9"));
        expect("igual no", !Version.newer("0.2.1", "0.2.1"));
        expect("vieja no", !Version.newer("0.2.0", "0.2.1"));
        expect("mayor", Version.newer("1.0.0", "0.99.99"));
        expect("ilegible no", !Version.newer("nightly", "0.2.1"));
        expect("instalada ilegible no", !Version.newer("0.3.0", "dev"));
        String h = "a".repeat(64), k = "B".repeat(64);
        String sums = h + "  otra.apk\n" + k + "  synctablet-0.2.2.apk\n";
        expect("suma", ("b".repeat(64)).equals(Version.sum(sums, "synctablet-0.2.2.apk")));
        expect("suma binaria", h.equals(Version.sum(h + " *x.apk", "x.apk")));
        expect("sin suma", Version.sum(sums, "no.apk") == null);
        expect("suma corta no vale", Version.sum("abc  x.apk", "x.apk") == null);
        System.out.println(fails == 0 ? "TODO BIEN" : fails + " FALLOS");
    }
}
