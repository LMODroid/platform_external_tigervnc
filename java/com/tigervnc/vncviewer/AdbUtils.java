package com.tigervnc.vncviewer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

    // Helpers
    private static String getNameForSn(String sn) {
        String[] result = runAdbFor(sn, "shell getprop ro.product.model").split("\n");
        for (String s : result) {
            String line = s.trim();
            if (line.isEmpty() || line.isBlank() || line.startsWith("*"))
                continue;
            return line + " (" + sn + ")";
        }
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
        try {
            Thread.sleep(2500); //TODO: don't..
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
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
        int vncPortDef = 9300;
        int vncPort = -1;
        while (vncPort == -1) {
            vncPort = trySetupForward(sn, "vncflinger", vncPortDef++);
        }
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
            if (forward[1].equals("tcp:" + port))
                return -1; // Port in use by adbd
            if (forward[0].equals(sn) && forward[2].equals("localabstract:" + socket)) // Same device already has forward
                return Integer.parseInt(forward[1].substring(5));
        }
        PrReturn r = runAdbForReal(sn, "forward tcp:" + port + " localabstract:" + socket);
        if (r.exitCode == 0)
            return port;
        else
            return -1; // Port probably in use by external application
    }

    // Util
    private static String adbExec;

    private static class PrReturn {
        public final int exitCode;
        public final String output;

        public PrReturn(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static PrReturn run(String command) {
        Runtime rt = Runtime.getRuntime();
        Process pr;
        try {
            pr = rt.exec(command);
        } catch (IOException | SecurityException | NullPointerException | IllegalArgumentException e) {
            return new PrReturn(-1, "failed to start: " + e.getMessage());
        }
        if (pr == null) {
            return new PrReturn(-1, "failed to start because pr == null");
        }
        int exitCode;
        try {
            exitCode = pr.waitFor();
        } catch (InterruptedException e) {
            return new PrReturn(-1, "failed to wait: " + e.getMessage());
        }
        String output;
        try {
            byte[] b = pr.getInputStream().readAllBytes();
            output = new String(b, 0, b.length);
        } catch (IOException e) {
            return new PrReturn(-1, "failed to parse: " + e.getMessage());
        }
        return new PrReturn(exitCode, output);
    }

    private static boolean tryAdb(String toTry) {
        PrReturn result = run(toTry + " devices");
        return result.exitCode == 0 && result.output.contains("List of devices attached");
    }

    private static boolean tryAdbFile(File toTry) {
        if (!toTry.exists())
            return false;
        return tryAdb(toTry.getAbsolutePath());
    }

    private static String findAdbExec() {
        //TODO: windows?
        File[] adbsToTry = new File[] { new File("/usr/bin/adb"), new File(System.getProperty("lmo.adbPath", "./adb")) };
        String[] adbsToTry2 = new String[] { "adb" };
        for (File adb : adbsToTry) {
            if (tryAdbFile(adb))
                return adb.getAbsolutePath();
        }
        for (String adb : adbsToTry2) {
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

    private static PrReturn runAdbReal(String command) {
        return run(requireAdbExec() + " " + command);
    }

    private static PrReturn runAdbForReal(String sn, String command) {
        return runAdbReal("-s " + sn + " " + command);
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
}