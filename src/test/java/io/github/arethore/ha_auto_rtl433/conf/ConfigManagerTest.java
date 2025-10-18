package io.github.arethore.ha_auto_rtl433.conf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ConfigManagerTest {

    private Config previousConfig;

    @Before
    public void captureExistingConfig() {
        previousConfig = ConfigManager.INSTANCE.getConfig();
    }

    @After
    public void restoreConfig() {
        ConfigManager.INSTANCE.setConfig(previousConfig);
    }

    @Test
    public void loadsExampleConfig() {
        Path configPath = Path.of("example_config", "config.yaml");
        ConfigManager.INSTANCE.setConfig(Optional.of(configPath.toString()));
        Config config = ConfigManager.INSTANCE.getConfig();
        assertNotNull(config);
        assertNotNull(config.getRtl433());
        assertNotNull(config.getRtl433().getWhitelist());
        assertEquals(6, config.getRtl433().getWhitelist().size());

        Config.Rtl433.WhitelistEntry first = config.getRtl433().getWhitelist().get(0);
        assertEquals("Esperanza-EWS", first.getModel());
        assertEquals("001", first.getHaId());
        assertEquals("001", first.getRtl433Id());
        assertEquals("Fridge Thermometer", first.getName());
        assertNotNull(first.getAllEntities());
        assertEquals(2, first.getAllEntities().size());
    }
}
