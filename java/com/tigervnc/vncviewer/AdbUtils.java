package com.tigervnc.vncviewer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.net.URISyntaxException;

public class AdbUtils {
    public static List<String> findOnlineDevices() {
        ArrayList<String> devices = new ArrayList<>();
        String[] lines = runAdb("devices").split("\n");
        for (String s : lines) {
            String line = s.trim();
            if (line.isEmpty() || line.isBlank() || line.contains("List of") || line.startsWith("*"))
                continue;
            String[] device = line.split("\t");
            assert device.length == 2;
            if ("device".equals(device[1]))
                devices.add(device[0]);
        }
        return devices;
    }

    public static List<String> findOnlineDevicesFancy() { // For GUI
        return findOnlineDevices().stream().map(AdbUtils::getNameForSn).collect(Collectors.toList());
    }

    public static String setupServerForDeviceMaybe(String value) {
        // Could be more comprehensive.
        if (value.contains(":")) // VNC server with port
            return value;
        if (value.contains(" ")) // Fancy device name (used by GUI): Pixel 6 Pro (SERIALABCDEF)
            value = getSnForName(value);
        return setupServerForSerial(value); // Direct serial (for command line start)
    }

    public static void maybeDesetupDevice() {
        int vncPort = Parameters.vncPort.getValue();
        int audioPort = Parameters.audioPort.getValue();
        String sn = getSnForForward(vncPort, "vncflinger");
        if (sn == null)
            return;
        if (!sn.equals(getSnForForward(audioPort, "audiostreamer")))
            return;
        runAdb("forward --remove tcp:" + vncPort);
        runAdb("forward --remove tcp:" + audioPort);
        if (Parameters.killDesktop.getValue())
            runAdbFor(sn, "shell am stop-service com.libremobileos.vncflinger/.VncFlinger");
    }

    // Helpers
    private static String getSnForForward(int port, String socket) {
        String[] forwards = runAdb("forward --list").split("\n");
        for (String s : forwards) {
            String line = s.trim();
            if (line.isEmpty() || line.isBlank() || line.startsWith("*"))
                continue;
            String[] forward = line.split(" ");
            assert forward.length == 3;
            if (forward[1].equals("tcp:" + port) && forward[2].equals("localabstract:" + socket))
                return forward[0];
        }
        return null;
    }

    private static String getNameForSn(String sn) {
        String result = normalizeOneliner(runAdbFor(sn, "shell getprop ro.product.model"));
        if (result != null)
            return result + " (" + sn + ")";
        return sn;
    }

    private static String getSnForName(String name) {
        for (String sn : findOnlineDevices()) {
            String[] result = runAdbFor(sn, "shell getprop ro.product.model").split("\n");
            for (String s : result) {
                String line = s.trim();
                if (line.isEmpty() || line.isBlank() || line.startsWith("*"))
                    continue;
                String fname = line + " (" + sn + ")";
                if (fname.equals(name))
                    return sn;
            }
        }
        throw new IllegalArgumentException("cannot find device");
    }

    private static boolean isDevicePresent(String sn) {
        return findOnlineDevices().contains(sn);
    }

    private static String setupServerForSerial(String sn) {
        assert isDevicePresent(sn);
        runAdbFor(sn, "shell am broadcast --allow-background-activity-starts -a com.libremobileos.desktopmode.START com.libremobileos.desktopmode/.VNCServiceController");
        // Phones without SIM card aren't guranteed to have the correct time
        long offset = (System.currentTimeMillis() / 1000L) - Long.parseLong(Objects.requireNonNull(normalizeOneliner(runAdbFor(sn, "shell date +%s"))));
        // (Ab)use logcat to search for one "Listening on @vncflinger" printed from when this command was executed
        runAdbFor(sn, "logcat -T " + ((System.currentTimeMillis() / 1000L) - offset) + ".000 -m 1 --regex \"Listening on @vncflinger\"", 10);
        int vncPort = setupForwardForSerial(sn);
        return ":" + vncPort;
    }

    private static int setupForwardForSerial(String sn) {
        int audioPortDef = Parameters.audioPort.getValue();
        int audioPort = -1;
        while (audioPort == -1) {
            audioPort = trySetupForward(sn, "audiostreamer", audioPortDef++);
        }
        Parameters.audioPort.setParam(audioPort);
        int vncPortDef = Parameters.vncPort.getValue();
        int vncPort = -1;
        while (vncPort == -1) {
            vncPort = trySetupForward(sn, "vncflinger", vncPortDef++);
        }
        Parameters.vncPort.setParam(vncPort);
        return vncPort;
    }

    private static int trySetupForward(String sn, String socket, int port) {
        String[] forwards = runAdb("forward --list").split("\n");
        for (String s : forwards) {
            String line = s.trim();
            if (line.isEmpty() || line.isBlank() || line.startsWith("*"))
                continue;
            String[] forward = line.split(" ");
            assert forward.length == 3;
            if (forward[0].equals(sn) && forward[2].equals("localabstract:" + socket)) // Same device already has forward
                return Integer.parseInt(forward[1].substring(4));
            if (forward[1].equals("tcp:" + port))
                return -1; // Port in use by adbd
        }
        PrReturn r = runAdbForReal(sn, "forward tcp:" + port + " localabstract:" + socket);
        if (r.exitCode == 0)
            return port;
        else
            return -1; // Port probably in use by external application
    }

    // Util
    private static String normalizeOneliner(String result) {
        String[] data = result.split("\n");
        for (String s : data) {
            String line = s.trim();
            if (line.isEmpty() || line.isBlank() || line.startsWith("*"))
                continue;
            return line;
        }
        return null;
    }
    private static String adbExec;

    private static class PrReturn {
        public final int exitCode;
        public final String output;
        public final String error;

        public PrReturn(int exitCode, String output, String error) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }
    }

    // https://stackoverflow.com/a/65057991
    private static final class StringUtilities {
        private static final List<Character> WORD_DELIMITERS = Arrays.asList(' ', '\t');
        private static final List<Character> QUOTE_CHARACTERS = Arrays.asList('"', '\'');
        private static final char ESCAPE_CHARACTER = '\\';
        private StringUtilities() {}
        public static String[] splitWords(String string) {
            StringBuilder wordBuilder = new StringBuilder();
            List<String> words = new ArrayList<>();
            char quote = 0;

            for (int i = 0; i < string.length(); i++) {
                char c = string.charAt(i);

                if (c == ESCAPE_CHARACTER && i + 1 < string.length()) {
                    wordBuilder.append(string.charAt(++i));
                } else if (WORD_DELIMITERS.contains(c) && quote == 0) {
                    words.add(wordBuilder.toString());
                    wordBuilder.setLength(0);
                } else if (quote == 0 && QUOTE_CHARACTERS.contains(c)) {
                    quote = c;
                } else if (quote == c) {
                    quote = 0;
                } else {
                    wordBuilder.append(c);
                }
            }

            if (wordBuilder.length() > 0) {
                words.add(wordBuilder.toString());
            }

            return words.toArray(new String[0]);
        }
    }

    private static PrReturn run(String command, int timeoutSec) {
        // Runtime.exec(String) uses StringTokenizer which does not support escaping
        String[] cmd = StringUtilities.splitWords(command);
        //System.out.println(Arrays.toString(cmd)); //DEBUG
        Runtime rt = Runtime.getRuntime();
        Process pr;
        try {
            pr = rt.exec(cmd);
        } catch (IOException | SecurityException | NullPointerException | IllegalArgumentException e) {
            return new PrReturn(-1, "", "failed to start: " + e.getMessage());
        }
        if (pr == null) {
            return new PrReturn(-1, "", "failed to start because pr == null");
        }
        int exitCode;
        try {
            if (timeoutSec == 0) {
                exitCode = pr.waitFor();
            } else {
                if (pr.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                    exitCode = pr.exitValue();
                } else {
                    pr.destroyForcibly();
                    throw new InterruptedException("timeout expired");
                }
            }
        } catch (InterruptedException | IllegalThreadStateException e) {
            return new PrReturn(-1, "", "failed to wait: " + e.getMessage());
        }
        String output, error;
        try {
            byte[] b = pr.getInputStream().readAllBytes();
            output = new String(b, 0, b.length);
            b = pr.getErrorStream().readAllBytes();
            error = new String(b, 0, b.length);
        } catch (IOException e) {
            return new PrReturn(-1, "", "failed to parse: " + e.getMessage());
        }
        return new PrReturn(exitCode, output, error);
    }

    private static boolean tryAdb(String toTry) {
        PrReturn result = run(toTry + " devices", 10);
        boolean s = result.exitCode == 0 && result.output.contains("List of devices attached");
        if (!s) {
            System.out.println("[adb] binary failed test, path=\"" + toTry + "\" exitCode=" + result.exitCode + " output=\"" + result.output + "\" error=\"" + result.error + "\"");
        }
        return s;
    }

    private static String findAdbExec() {
        ArrayList<String> adbsToTry = new ArrayList<String>();
        adbsToTry.add(System.getProperty("lmo.adbPath", "." + File.separator + "adb"));
        adbsToTry.add("adb");
        adbsToTry.add("adb.exe");
        try {
            File currentJarDir = new File(AdbUtils.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            adbsToTry.add(currentJarDir.getParentFile().getPath() + File.separator + "adb");
        } catch (URISyntaxException e) {
            System.err.println("WARN: Failed to get jar path!");
        }
        for (String adb : adbsToTry) {
            adb = "\"" + adb.replace("\\", "\\\\") + "\"";
            if (tryAdb(adb))
                return adb;
        }
        System.err.println("WARN: did not find adb!!");
        return null;
    }

    private static String getAdbExec() {
        if (adbExec == null) {
            adbExec = findAdbExec();
            if (adbExec != null) {
                System.out.println("found adb: " + adbExec);
            }
        }
        return adbExec;
    }

    private static String requireAdbExec() {
        String adbExec = getAdbExec();
        if (adbExec == null) {
            throw new IllegalStateException("adb not found");
        }
        return adbExec;
    }

    private static PrReturn runAdbReal(String command, int timeoutsec) {
        return run(requireAdbExec() + " " + command, timeoutsec);
    }

    private static PrReturn runAdbReal(String command) {
        return runAdbReal(command, 0);
    }

    private static PrReturn runAdbForReal(String sn, String command, int timeoutsec) {
        return runAdbReal("-s " + sn + " " + command, timeoutsec);
    }

    private static PrReturn runAdbForReal(String sn, String command) {
        return runAdbForReal(sn, command, 0);
    }

    private static String assertOk(PrReturn r) {
        assert r.exitCode == 0;
        return r.output;
    }

    private static String runAdb(String command) {
        return assertOk(runAdbReal(command));
    }

    private static String runAdbFor(String sn, String command) {
        return assertOk(runAdbForReal(sn, command));
    }

    private static String runAdb(String command, int timeoutsec) {
        return assertOk(runAdbReal(command, timeoutsec));
    }

    private static String runAdbFor(String sn, String command, int timeoutsec) {
        return assertOk(runAdbForReal(sn, command, timeoutsec));
    }
}
