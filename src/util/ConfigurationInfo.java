package util;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class ConfigurationInfo {
    public final static int NUMBER_OF_VEHICLES = 20;
    public final static int NUMBER_OF_VICTIMS_PER_RESCUE = 50;
    public final static String[] mapFiles = {
            "data/map.20.graphml",
            "data/map.100.graphml",
            "data/map.2000.graphml",
            "data/map.5000.graphml",
            "data/map.100000.graphml",
            "data/manhattan.graphml"
    };
    public final static String[] origins = {
            "1",
            "1",
            "1",
            "1",
            "1",
            "42459137"
    };

    public static String getMapFile(String CFG_FILE) {
        Properties props = loadConfig(CFG_FILE);
        int mapId = Integer.parseInt(props.getProperty("MAP", "1"));
        return mapFiles[mapId];
    }

    public static String getOrigin(String CFG_FILE) {
        Properties props = loadConfig(CFG_FILE);
        int mapId = Integer.parseInt(props.getProperty("MAP", "1"));
        return origins[mapId];
    }

    public static Properties loadConfig(String CFG_FILE) {
        Properties p = new Properties();
        try {
            p.load(new FileInputStream(CFG_FILE));
        } catch (IOException ignored) {}
        return p;
    }
}
